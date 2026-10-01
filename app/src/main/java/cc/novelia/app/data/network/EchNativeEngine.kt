package cc.novelia.app.data.network

import android.content.Context
import cc.novelia.app.data.storage.appJson
import cc.novelia.nativeech.ech.Ech
import cc.novelia.nativeech.ech.Upload
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.encodeToString
import okhttp3.Headers
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okio.Buffer
import okio.Pipe
import okio.buffer

/** The only production Kotlin file that knows the JNI types or their null-at-EOF convention. */
internal class EchNativeEngine(context: Context) : EchEngine {
    private val applicationContext = context.applicationContext
    private val nativeDelegate = lazy { go.Seq.setContext(applicationContext); Ech.newClient() }
    private val native by nativeDelegate

    override fun open(request: Request, timeouts: EchTimeouts): EchExchange {
        val upload = request.body?.let(::StreamingUpload)
        val headers = request.headers.toMultimap().toMutableMap()
        if (request.header("Content-Type") == null) {
            request.body?.contentType()?.let { headers["Content-Type"] = listOf(it.toString()) }
        }
        val call = try {
            native.newCall(request.method, request.url.toString(), appJson.encodeToString(headers), upload,
                request.body?.contentLength() ?: 0, timeouts.connectMillis, timeouts.readMillis, timeouts.writeMillis)
        } catch (error: Exception) { upload?.close(); throw error }
        return object : EchExchange {
            override fun execute(): EchReply {
                upload?.start()
                val reply = try { call.execute() }
                catch (_: Exception) { throw connectionFailure(call.failureReason()) }
                val responseHeaders = Headers.Builder()
                appJson.decodeFromString<Map<String, List<String>>>(reply.headersJSON()).forEach { (name, values) ->
                    if (name.lowercase() !in setOf("connection", "transfer-encoding")) {
                        values.forEach { responseHeaders.add(name, it) }
                    }
                }
                val protocol = if (reply.protocol() == "HTTP/2.0") Protocol.HTTP_2 else Protocol.HTTP_1_1
                return EchReply(reply.statusCode().toInt(), protocol, responseHeaders.build(), reply.contentLength())
            }

            override fun read(maxBytes: Long): ByteArray = try {
                // gomobile maps an empty Go slice to Java null: both represent normal EOF here.
                call.read(maxBytes) ?: ByteArray(0)
            } catch (_: Exception) { throw connectionFailure(call.failureReason(), "响应读取失败") }

            override fun cancel() { call.cancel(); upload?.close() }
        }
    }

    fun probe(host: String): String = native.probe(host)
    fun resetNetworkState() { if (nativeDelegate.isInitialized()) native.resetNetworkState() }
}

private fun connectionFailure(reason: String?, fallback: String = "连接失败"): EchIOException {
    val safeReason = reason?.takeIf { it in setOf("连接超时", "请求发送超时", "响应读取超时", "连接被中断",
        "证书验证未通过", "服务器拒绝 ECH 配置", "TLS 协商未完成") } ?: fallback
    return EchIOException("ECH $safeReason，请在设置中运行连接诊断")
}

/** At most 64 KiB buffered per upload; request data never needs a temporary file. */
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
        private val writers = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "novelia-ech-upload").apply { isDaemon = true }
        }
    }
}
