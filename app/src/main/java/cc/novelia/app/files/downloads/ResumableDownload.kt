package cc.novelia.app.files.downloads

import cc.novelia.app.data.network.ApiException
import cc.novelia.app.data.network.ManagedConnectAttempts
import cc.novelia.app.data.network.NetworkConnections
import cc.novelia.app.data.network.RecoverableNetworkRead
import cc.novelia.app.data.network.isRecoverableNetworkFailure
import cc.novelia.app.data.network.waitForNetworkRecovery
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ProtocolException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Request
import okhttp3.Response

private const val MAX_DOWNLOAD_BYTES = 512L * 1024 * 1024
private class DownloadStorageException(cause: IOException) : IOException("无法写入下载文件，请检查存储空间", cause)
private class RestartDownload : IOException()
private data class DownloadVersion(val etag: String, val length: Long, val url: String)

/** Resume only this worker's owned partial; response validation happens before any bytes are appended. */
internal suspend fun resumableDownload(request: Request, partial: File, connections: NetworkConnections?,
    ensureCurrent: () -> Unit, onProgress: (Long, Long) -> Unit,
    execute: suspend (Request, (Response) -> Unit) -> Unit) {
    var version: DownloadVersion? = null
    repeat(3) { attempt ->
        currentCoroutineContext().ensureActive()
        ensureCurrent()
        val previous = version
        val offset = if (previous != null) partial.length() else 0L
        val generation = connections?.generation()
        val next = request.newBuilder().header("Accept-Encoding", "identity")
            .tag(RecoverableNetworkRead::class.java, RecoverableNetworkRead)
            .tag(ManagedConnectAttempts::class.java, ManagedConnectAttempts)
            .apply {
                removeHeader("Range"); removeHeader("If-Range")
                if (offset > 0 && previous != null) {
                    header("Range", "bytes=$offset-"); header("If-Range", previous.etag)
                }
            }.build()
        try {
            execute(next) { response ->
                ensureCurrent()
                if (response.code == 416 && offset > 0) throw RestartDownload()
                if (!response.isSuccessful) throw ApiException(response.code, "下载失败（${response.code}），请稍后重试")
                val body = response.body ?: throw ProtocolException("服务器没有返回文件")
                if (body.contentType().toString().startsWith("text/html")) throw ProtocolException("服务器返回了网页，请稍后重试")
                if (response.header("Content-Encoding")?.let { !it.equals("identity", true) } == true)
                    throw ProtocolException("下载编码不支持安全续传")
                val etag = response.header("ETag")?.takeIf {
                    it.length >= 2 && it.startsWith('"') && it.endsWith('"') &&
                        it.substring(1, it.length - 1).all { c -> c.code >= 0x21 && c != '"' && c.code != 0x7f }
                }
                var start = 0L
                val expected = if (response.code == 206) {
                    if (offset <= 0 || previous == null) throw ProtocolException("服务器返回了意外的文件范围")
                    val range = parseDownloadRange(response.header("Content-Range"))
                    if (range == null || range.first != offset || range.third != previous.length ||
                        range.second != previous.length - 1 || etag != previous.etag || response.request.url.toString() != previous.url ||
                        (body.contentLength() >= 0 && body.contentLength() != range.second - range.first + 1)) throw RestartDownload()
                    start = offset
                    previous.length
                } else {
                    if (response.code != 200) throw ProtocolException("服务器返回了不支持的下载响应")
                    body.contentLength()
                }
                if (expected > MAX_DOWNLOAD_BYTES) throw ProtocolException("文件超过 512 MB")
                version = if (etag != null && expected > 0) DownloadVersion(etag, expected, response.request.url.toString()) else null
                var total = start
                val output = try { object : FileOutputStream(partial, start > 0) {
                    override fun close() {
                        try { super.close() } catch (e: IOException) { throw DownloadStorageException(e) }
                    }
                } } catch (e: IOException) { throw DownloadStorageException(e) }
                output.use { sink ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            ensureCurrent()
                            val count = input.read(buffer)
                            ensureCurrent()
                            if (count < 0) break
                            total += count
                            if (total > MAX_DOWNLOAD_BYTES || (expected >= 0 && total > expected)) throw ProtocolException("下载文件大小不匹配")
                            try { sink.write(buffer, 0, count) } catch (e: IOException) { throw DownloadStorageException(e) }
                            onProgress(total, expected)
                        }
                    }
                }
                if (total == 0L) throw ProtocolException("下载内容为空")
                if (expected >= 0 && total != expected) throw IOException("下载连接提前中断")
            }
            return
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            ensureCurrent()
            if (attempt == 2 || error is DownloadStorageException || !error.isRecoverableNetworkFailure()) throw error
            if (error is RestartDownload) { version = null }
            else waitForNetworkRecovery(connections, generation, error)
        }
    }
}

internal fun parseDownloadRange(value: String?): Triple<Long, Long, Long>? {
    val parts = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(value.orEmpty()) ?: return null
    val start = parts.groupValues[1].toLongOrNull() ?: return null
    val end = parts.groupValues[2].toLongOrNull() ?: return null
    val size = parts.groupValues[3].toLongOrNull() ?: return null
    return if (start <= end && end < size && size <= MAX_DOWNLOAD_BYTES) Triple(start, end, size) else null
}
