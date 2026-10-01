package cc.novelia.app.data.network

import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
    fun open(request: Request, timeoutMillis: Long): EchExchange
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
        repeat(11) { redirectCount ->
            if (!request.url.isHttps || request.url.port != 443) throw IOException("ECH 仅支持 HTTPS 443")
            if (chain.call().isCanceled()) throw IOException("Canceled")
            if (request.body?.isDuplex() == true) throw IOException("ECH 暂不支持双工请求")
            val response = execute(chain, request)
            val next = if (redirects) redirectRequest(response) else null
            if (next == null) return response
            response.close()
            if (redirectCount == 10) throw IOException("重定向次数过多")
            request = next
            // 外部图片/下载站仍交给原客户端；跨来源凭据已移除。
            if (request.url.host !in echHosts) return chain.proceed(request)
        }
        throw IOException("重定向次数过多")
    }

    private fun execute(chain: Interceptor.Chain, request: Request): Response {
        val started = System.currentTimeMillis()
        val timeout = chain.readTimeoutMillis().toLong().takeIf { it > 0 } ?: TimeUnit.HOURS.toMillis(24)
        val exchange = try { engine.open(request, timeout) }
        catch (_: LinkageError) { throw EchIOException("当前设备无法加载 ECH 本地库，可在设置中关闭 ECH") }
        catch (_: Exception) { throw EchIOException("ECH 初始化失败，请运行连接诊断") }
        val closed = AtomicBoolean(false)
        val monitor = cancellationMonitor.scheduleWithFixedDelay({
            if (chain.call().isCanceled()) exchange.cancel()
        }, 0, 100, TimeUnit.MILLISECONDS)
        fun finishExchange() {
            if (closed.compareAndSet(false, true)) {
                monitor.cancel(false)
                exchange.cancel()
            }
        }
        try {
            val reply = exchange.execute()
            if (chain.call().isCanceled()) throw IOException("Canceled")
            val source = object : Source {
                private var eof = false
                override fun timeout() = Timeout.NONE
                override fun close() = finishExchange()
                override fun read(sink: Buffer, byteCount: Long): Long {
                    require(byteCount >= 0)
                    if (byteCount == 0L) return 0
                    if (eof) return -1
                    if (closed.get() || chain.call().isCanceled()) throw IOException("Canceled")
                    val bytes = try { exchange.read(minOf(byteCount, 65536)) }
                    catch (_: Exception) { finishExchange(); throw EchIOException("ECH 响应读取失败，请运行连接诊断") }
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
            if (chain.call().isCanceled()) throw IOException("Canceled")
            throw error as? EchIOException ?: EchIOException("ECH 连接失败，请在设置中运行连接诊断")
        }
    }

    companion object {
        private val cancellationMonitor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "novelia-ech-cancel").apply { isDaemon = true }
        }
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
        interceptors().remove(existing)
        addInterceptor(existing.withRedirects(enabled))
    }
}
