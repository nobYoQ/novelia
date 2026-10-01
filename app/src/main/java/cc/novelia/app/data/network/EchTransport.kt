package cc.novelia.app.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import cc.novelia.app.data.storage.appJson
import cc.novelia.nativeech.ech.Ech
import cc.novelia.nativeech.ech.Upload
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okio.Buffer
import okio.Pipe
import okio.buffer

/** 此试验分支默认开启；偏好只保存在设备中，不导出账号或诊断日志。 */
class EchTransport(context: Context) {
    private val preferences = context.getSharedPreferences("ech-transport", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(preferences.getBoolean("enabled", true))
    val enabled = state.asStateFlow()
    private val nativeDelegate = lazy { go.Seq.setContext(context.applicationContext); Ech.newClient() }
    private val native by nativeDelegate
    private val engine = object : EchEngine {
        override fun open(request: Request, timeoutMillis: Long): EchExchange {
            val upload = request.body?.let(::StreamingUpload)
            val headers = request.headers.toMultimap().toMutableMap()
            if (request.header("Content-Type") == null) request.body?.contentType()?.let { headers["Content-Type"] = listOf(it.toString()) }
            val call = try {
                native.newCall(request.method, request.url.toString(), appJson.encodeToString(headers), upload,
                    request.body?.contentLength() ?: 0, timeoutMillis)
            } catch (e: Exception) { upload?.close(); throw e }
            return object : EchExchange {
                override fun execute(): EchReply {
                    upload?.start()
                    val reply = try { call.execute() } catch (_: Exception) {
                        val reason = call.failureReason().takeIf { it in setOf("连接超时", "连接被中断", "证书验证未通过", "服务器拒绝 ECH 配置", "TLS 协商未完成") } ?: "连接失败"
                        throw EchIOException("ECH $reason，请在设置中运行连接诊断")
                    }
                    val responseHeaders = Headers.Builder()
                    appJson.decodeFromString<Map<String, List<String>>>(reply.headersJSON()).forEach { (name, values) ->
                        if (name.lowercase() !in setOf("connection", "transfer-encoding")) values.forEach { responseHeaders.add(name, it) }
                    }
                    val protocol = if (reply.protocol() == "HTTP/2.0") Protocol.HTTP_2 else Protocol.HTTP_1_1
                    return EchReply(reply.statusCode().toInt(), protocol, responseHeaders.build(), reply.contentLength())
                }
                // gomobile 将 Go 的空切片映射为 null；这里统一为流结束标记。
                override fun read(maxBytes: Long): ByteArray = call.read(maxBytes) ?: ByteArray(0)
                override fun cancel() { call.cancel(); upload?.close() }
            }
        }
    }
    // 独立匿名客户端，但使用与实际 API 相同的 JNI / OkHttp 响应读取路径。
    private val diagnosticClient by lazy {
        OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).followRedirects(false)
            .addInterceptor(EchInterceptor(engine, { true })).build()
    }

    init {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        connectivity?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { if (nativeDelegate.isInitialized()) native.resetNetworkState() }
            override fun onLost(network: Network) { if (nativeDelegate.isInitialized()) native.resetNetworkState() }
        })
    }

    fun setEnabled(value: Boolean) {
        preferences.edit().putBoolean("enabled", value).apply()
        state.value = value
    }

    fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .addInterceptor(EchInterceptor(engine, { state.value }))
        .build()

    /** 主动点击才运行；检查握手和完整 HTTP 读取，不携带账号或输出响应内容。 */
    suspend fun diagnose(): String = coroutineScope {
        val checks = echHosts.map { host -> async(Dispatchers.IO) {
            val start = System.nanoTime()
            val result = try { native.probe(host) }
            catch (_: LinkageError) { "当前设备无法加载 ECH 本地库" }
            catch (_: Exception) { "ECH 库初始化或诊断失败" }
            val handshakeMillis = (System.nanoTime() - start) / 1_000_000
            val httpStart = System.nanoTime()
            var stage = "HTTP 请求失败（未收到响应头）"
            val httpResult = try {
                val request = Request.Builder().url("https://$host/cdn-cgi/trace").get().build()
                diagnosticClient.newCall(request).execute().use { response ->
                    stage = "HTTP ${response.code}，响应读取失败"
                    val source = response.body?.source() ?: throw IOException("Missing body")
                    val buffer = Buffer()
                    var length = 0L
                    while (true) {
                        val count = source.read(buffer, 4096)
                        if (count == -1L) break
                        length += count
                        buffer.clear()
                        if (length > 65536) throw IOException("Diagnostic response too large")
                    }
                    "HTTP ${response.code} · ${response.protocol} · 响应完整读取"
                }
            } catch (_: LinkageError) { "当前设备无法加载 ECH 本地库" }
            catch (error: EchIOException) { "$stage：${error.message}" }
            catch (_: Exception) { stage }
            "$host\n$result · $handshakeMillis ms\n$httpResult · ${(System.nanoTime() - httpStart) / 1_000_000} ms"
        } }
        checks.awaitAll().joinToString("\n\n") + "\n\n" + withContext(Dispatchers.IO) { diagnoseForumApi() }
    }

    /** Keep future forum connectivity verifiable without importing its business or session layer. */
    private fun diagnoseForumApi(): String = listOf(
        "论坛分类 API" to EchForumProbe.CATEGORIES_URL,
        "论坛帖子 API" to EchForumProbe.POSTS_URL
    ).mapIndexed { index, (label, url) ->
        val start = System.nanoTime()
        var stage = "未收到响应头"
        val result = try {
            val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
            diagnosticClient.newCall(request).execute().use { response ->
                stage = "HTTP ${response.code}，正文读取失败"
                if (!response.isSuccessful) "HTTP ${response.code}"
                else {
                    val source = response.body?.source() ?: throw IOException("Missing body")
                    if (source.request(2L * 1024 * 1024 + 1)) throw IOException("Diagnostic response too large")
                    val json = source.readUtf8()
                    stage = "HTTP ${response.code}，JSON 解析失败"
                    val count = if (index == 0) EchForumProbe.categoryCount(json) else EchForumProbe.postCount(json)
                    "HTTP ${response.code}，读取和解析成功（$count 项）"
                }
            }
        } catch (_: LinkageError) { "当前设备无法加载 ECH 本地库" }
        catch (error: EchIOException) { "$stage：${error.message}" }
        catch (_: Exception) { stage }
        "$label\n$result · ${(System.nanoTime() - start) / 1_000_000} ms"
    }.joinToString("\n\n")

    suspend fun resetConnections() = withContext(Dispatchers.IO) {
        if (nativeDelegate.isInitialized()) native.resetNetworkState()
    }
}

/** 上传最多缓冲 64 KiB，既不整本载入内存，也不把请求正文写入临时文件。 */
private class StreamingUpload(private val body: RequestBody) : Upload {
    private val pipe = Pipe(65536)
    private val failure = AtomicReference<IOException?>(null)
    fun start() {
        writers.execute {
            try { pipe.sink.buffer().use { body.writeTo(it) } }
            catch (_: Exception) { failure.set(IOException("ECH 上传中断")); pipe.cancel() }
        }
    }
    override fun readChunk(maxBytes: Long): ByteArray {
        failure.get()?.let { throw it }
        val buffer = Buffer()
        val count = pipe.source.read(buffer, minOf(maxBytes, 65536))
        failure.get()?.let { throw it }
        return if (count == -1L) ByteArray(0) else buffer.readByteArray()
    }
    override fun close() { pipe.cancel() }
    companion object {
        private val writers = Executors.newCachedThreadPool { runnable -> Thread(runnable, "novelia-ech-upload").apply { isDaemon = true } }
    }
}
