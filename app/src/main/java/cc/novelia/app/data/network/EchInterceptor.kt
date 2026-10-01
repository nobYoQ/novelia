package cc.novelia.app.data.network

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer

internal val echHosts = setOf("n.novelia.cc", "auth.novelia.cc", "forum.novelia.cc")

/** Only fixed local diagnostic labels are allowed in user-visible messages. */
internal class EchIOException(message: String) : IOException(message)

internal interface EchEngine {
    fun open(request: Request, timeouts: EchTimeouts): EchExchange
}

internal interface EchExchange {
    fun execute(): EchReply
    fun read(maxBytes: Long): ByteArray
    fun cancel()
}

internal data class EchReply(val code: Int, val protocol: Protocol, val headers: Headers, val length: Long)

/** ECH 必须成功才返回响应；不因失败重放写请求或降级到明文 SNI。 */
internal class EchInterceptor(
    private val engine: EchEngine,
    private val enabled: () -> Boolean,
    internal val redirects: Boolean = false
) : Interceptor {
    fun withRedirects(value: Boolean) = EchInterceptor(engine, enabled, value)

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        if (!enabled() || request.url.host !in echHosts) return chain.proceed(request)
        val lifetime = CallLifetime(chain)
        try {
            repeat(11) { redirectCount ->
                if (!request.url.isHttps || request.url.port != 443) throw IOException("ECH 仅支持 HTTPS 443")
                lifetime.checkActive()
                if (request.body?.isDuplex() == true) throw IOException("ECH 暂不支持双工请求")
                val response = execute(chain, request, lifetime)
                val next = if (redirects) redirectRequest(response) else null
                if (next == null) {
                    lifetime.finalBody.set(true)
                    lifetime.checkActive()
                    return response
                }
                response.close()
                if (redirectCount == 10) throw IOException("重定向次数过多")
                request = next
                // 外部图片/下载站仍交给原客户端；跨来源凭据已移除。
                if (request.url.host !in echHosts) {
                    lifetime.close()
                    return chain.proceed(request)
                }
            }
            throw IOException("重定向次数过多")
        } catch (error: Exception) { lifetime.close(); throw error }
    }

    private fun execute(chain: Interceptor.Chain, request: Request, lifetime: CallLifetime): Response {
        val started = System.currentTimeMillis()
        val timeouts = EchTimeouts(
            chain.connectTimeoutMillis().toLong(), chain.readTimeoutMillis().toLong(), chain.writeTimeoutMillis().toLong()
        )
        val exchange = try { engine.open(request, timeouts) }
        catch (_: LinkageError) { throw EchIOException("当前设备无法加载 ECH 本地库，可在设置中关闭 ECH") }
        catch (_: Exception) { throw EchIOException("ECH 初始化失败，请运行连接诊断") }
        val closed = AtomicBoolean(false)
        val monitor = AtomicReference<ScheduledFuture<*>?>()
        fun finishExchange() {
            if (closed.compareAndSet(false, true)) {
                monitor.get()?.cancel(false)
                exchange.cancel()
                if (lifetime.finalBody.get()) lifetime.close()
            }
        }
        monitor.set(cancellationMonitor.scheduleWithFixedDelay({
            if (chain.call().isCanceled()) finishExchange()
        }, 0, 50, TimeUnit.MILLISECONDS))
        if (closed.get()) monitor.get()?.cancel(false)
        try {
            val reply = exchange.execute()
            lifetime.checkActive()
            val source = object : Source {
                private var eof = false
                override fun timeout() = Timeout.NONE
                override fun close() = finishExchange()
                override fun read(sink: Buffer, byteCount: Long): Long {
                    require(byteCount >= 0)
                    if (byteCount == 0L) return 0
                    if (eof) return -1
                    lifetime.checkActive()
                    if (closed.get()) throw IOException("Canceled")
                    val bytes = try { exchange.read(minOf(byteCount, 65536)) }
                    catch (error: Exception) {
                        finishExchange()
                        lifetime.checkActive()
                        throw error as? EchIOException ?: EchIOException("ECH 响应读取失败，请运行连接诊断")
                    }
                    lifetime.checkActive()
                    if (bytes.isEmpty()) { eof = true; finishExchange(); return -1 }
                    sink.write(bytes)
                    return bytes.size.toLong()
                }
            }.buffer()
            return Response.Builder().request(request).protocol(reply.protocol).code(reply.code).message("")
                .headers(reply.headers)
                .body(object : ResponseBody() {
                    override fun contentType() = reply.headers["Content-Type"]?.toMediaTypeOrNull()
                    override fun contentLength() = reply.length
                    override fun source() = source
                })
                .sentRequestAtMillis(started).receivedResponseAtMillis(System.currentTimeMillis()).build()
        } catch (error: Exception) {
            finishExchange()
            lifetime.checkActive()
            throw error as? EchIOException ?: EchIOException("ECH 连接失败，请在设置中运行连接诊断")
        }
    }

    /** Application-interceptor responses bypass OkHttp's exchange/body lifecycle. */
    private class CallLifetime(private val chain: Interceptor.Chain) {
        val finalBody = AtomicBoolean(false)
        private val timedOut = AtomicBoolean(false)
        private val finished = AtomicBoolean(false)
        private val timeoutTask: ScheduledFuture<*>?
        init {
            val timeout = chain.call().timeout()
            val remaining = if (timeout.hasDeadline()) (timeout.deadlineNanoTime() - System.nanoTime()).coerceAtLeast(1) else 0L
            val budget = listOf(timeout.timeoutNanos(), remaining).filter { it > 0 }.minOrNull()
            timeoutTask = budget?.let { cancellationMonitor.schedule({
                if (finished.compareAndSet(false, true)) {
                    timedOut.set(true)
                    chain.call().cancel()
                }
            }, it, TimeUnit.NANOSECONDS) }
        }
        fun checkActive() {
            if (timedOut.get()) throw InterruptedIOException("timeout")
            if (chain.call().isCanceled()) throw IOException("Canceled")
        }
        fun close() { finished.set(true); timeoutTask?.cancel(false) }
    }

    companion object {
        private val cancellationMonitor = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "novelia-ech-cancel").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
}

/** 只跟随 HTTPS；307/308 的一次性请求体不能重放，跨来源禁止携带账号凭据。 */
internal fun redirectRequest(response: Response): Request? {
    if (response.code !in setOf(300, 301, 302, 303, 307, 308)) return null
    val target = response.header("Location")?.let(response.request.url::resolve) ?: return null
    if (!target.isHttps || target.username.isNotEmpty() || target.password.isNotEmpty()) return null
    val original = response.request
    val changeToGet = response.code in setOf(300, 301, 302, 303) && original.method != "GET" && original.method != "HEAD"
    if (!changeToGet && original.body?.isOneShot() == true) return null
    return original.newBuilder().url(target).apply {
        removeHeader("Host")
        if (changeToGet) {
            method("GET", null)
            removeHeader("Content-Type"); removeHeader("Content-Length"); removeHeader("Transfer-Encoding")
        }
        if (!sameOrigin(original.url, target)) {
            removeHeader("Authorization"); removeHeader("Cookie"); removeHeader("Proxy-Authorization")
        }
    }.build()
}

private fun sameOrigin(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host == b.host && a.port == b.port

/** newBuilder 保留拦截器，因此同步更新桥接层的重定向策略。 */
internal fun OkHttpClient.Builder.echRedirects(enabled: Boolean): OkHttpClient.Builder = apply {
    followRedirects(enabled)
    val existing = interceptors().filterIsInstance<EchInterceptor>().singleOrNull()
    if (existing != null) {
        // Preserve ordering: later interceptors must not move ahead of the ECH route.
        val index = interceptors().indexOf(existing)
        interceptors()[index] = existing.withRedirects(enabled)
    }
}
