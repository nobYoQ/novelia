package cc.novelia.app.data.webdav

import cc.novelia.app.data.network.awaitBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import okhttp3.CookieJar
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

enum class WebDavFailure { AUTHENTICATION, PERMISSION, NOT_FOUND, CONFLICT, STORAGE, SERVER, NETWORK, INSECURE, UNSUPPORTED, INVALID_DATA }

class WebDavException(val failure: WebDavFailure, message: String, val statusCode: Int? = null) : IOException(message)

data class WebDavResource(val data: ByteArray, val etag: String?, val notModified: Boolean = false)

/** 地址不含账号、查询或片段；路径不允许跳出用户配置的目录。 */
internal object WebDavPaths {
    fun endpoint(config: WebDavConfig): HttpUrl {
        val raw = config.endpoint.trim()
        val uri = runCatching { URI(raw) }.getOrNull()
        require(uri != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "服务地址不能包含账号、查询参数或片段" }
        val url = raw.toHttpUrlOrNull() ?: throw IllegalArgumentException("请填写完整的 WebDAV 服务地址")
        require(url.username.isEmpty() && url.password.isEmpty()) { "请在用户名和密码栏填写凭据" }
        require(url.scheme == "https" || config.allowInsecureHttp) { "请使用 HTTPS 服务地址" }
        val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotBlank() }
        require(segments.none { forbiddenSegment(it) }) { "服务地址包含无效路径" }
        return if(url.encodedPath.endsWith('/')) url else url.newBuilder().addPathSegment("").build()
    }

    fun folderSegments(folder: String): List<String> {
        require(folder.length <= 512 && '\\' !in folder && folder.none { it.isISOControl() }) { "同步目录格式无效" }
        val parts = folder.trim().trim('/').split('/').filter { it.isNotEmpty() }
        require(parts.isNotEmpty() && parts.none { forbiddenSegment(it) || '%' in it || it.length > 120 }) { "请填写有效的相对同步目录" }
        return parts
    }

    private fun forbiddenSegment(segment: String): Boolean {
        val decoded = segment.replace(Regex("%([0-9a-fA-F]{2})")) { it.groupValues[1].toInt(16).toChar().toString() }
        return decoded == "." || decoded == ".." || decoded.any { it == '/' || it == '\\' || it.isISOControl() }
    }
}

/** 独立连接栈；没有原站认证、Cookie、ECH 或网络日志拦截器。 */
class WebDavClient(config: WebDavConfig, password: String, transport: OkHttpClient? = null) {
    private val endpoint = WebDavPaths.endpoint(config)
    private val folderSegments = WebDavPaths.folderSegments(config.folder)
    private val directory = folderSegments.fold(endpoint) { url, segment -> url.newBuilder().addPathSegment(segment).build() }
        .newBuilder().addPathSegment("").build()
    private val authorization = if(config.username.isEmpty() && password.isEmpty()) null else Credentials.basic(config.username, password, Charsets.UTF_8)
    private val client = (transport ?: defaultTransport).newBuilder()
        .cookieJar(CookieJar.NO_COOKIES).authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE)
        .followRedirects(false).followSslRedirects(false)
        // 由同步引擎读取最新远端后决定重试，避免隐式重发已经可能提交的写请求。
        .retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS)
        .apply { interceptors().clear(); networkInterceptors().clear() }.build()

    suspend fun ensureDirectory() {
        var parent = endpoint
        for(segment in folderSegments) {
            parent = parent.newBuilder().addPathSegment(segment).addPathSegment("").build()
            val response = request(parent, "MKCOL")
            when(response.code) {
                201, 200, 204 -> Unit
                405 -> {
                    val existing = request(parent, "PROPFIND", headers = mapOf("Depth" to "0"), body = PROPFIND.toByteArray())
                    if(existing.code != 207 && existing.code != 200) fail(existing.code)
                    if(!existing.body.toString(Charsets.UTF_8).contains(Regex("<(?:[A-Za-z0-9_-]+:)?collection(?:\\s[^>]*)?/?>"))) {
                        throw WebDavException(WebDavFailure.UNSUPPORTED, "同步目录不是有效的 WebDAV 文件夹")
                    }
                }
                else -> fail(response.code)
            }
        }
    }

    suspend fun get(name: String, etag: String? = null): WebDavResource? {
        val headers = if(etag == null) emptyMap() else mapOf("If-None-Match" to requireStrongEtag(etag))
        val response = request(file(name), "GET", headers)
        return when(response.code) {
            200 -> WebDavResource(response.body, response.etag)
            304 -> WebDavResource(ByteArray(0), response.etag ?: etag, true)
            404 -> null
            else -> fail(response.code)
        }
    }

    /** 所有上传都需指定已有版本或明确只创建新文件。 */
    suspend fun put(name: String, data: ByteArray, etag: String? = null, createOnly: Boolean = false): String {
        require(data.size <= MAX_RESPONSE_BYTES) { "同步数据过大" }
        require(createOnly.xor(etag != null)) { "上传需要指定当前版本或仅创建新文件" }
        val headers = if(createOnly) mapOf("If-None-Match" to "*") else mapOf("If-Match" to requireStrongEtag(etag!!))
        val response = request(file(name), "PUT", headers, data)
        if(response.code !in setOf(200, 201, 204)) fail(response.code)
        val returned = response.etag
        if(returned != null) return requireStrongEtag(returned)
        // 部分 WebDAV 服务不在 PUT 返回版本，读取并核对后再确认本次上传。
        val saved = get(name) ?: throw WebDavException(WebDavFailure.INVALID_DATA, "上传后的文件无法读取，请重试")
        if(!saved.data.contentEquals(data)) throw WebDavException(WebDavFailure.CONFLICT, "远端数据已被其他设备修改，请重试")
        return requireStrongEtag(saved.etag)
    }

    /** 唯一临时文件检测实际读写和并发保护；只清理本次成功创建的文件。 */
    suspend fun testConnection() {
        ensureDirectory()
        val name = ".novelia-probe-${UUID.randomUUID()}.txt"
        var created = false
        var knownEtag: String? = null
        try {
            val first = "novelia-probe-${UUID.randomUUID()}".toByteArray()
            val create = request(file(name), "PUT", mapOf("If-None-Match" to "*"), first)
            if(create.code !in setOf(200, 201, 204)) fail(create.code)
            created = true
            val read = get(name) ?: throw WebDavException(WebDavFailure.UNSUPPORTED, "服务无法读取刚创建的测试文件")
            if(!read.data.contentEquals(first)) throw WebDavException(WebDavFailure.UNSUPPORTED, "服务返回的测试内容不一致")
            val old = requireStrongEtag(read.etag)
            knownEtag = old
            val duplicate = request(file(name), "PUT", mapOf("If-None-Match" to "*"), "duplicate".toByteArray())
            if(duplicate.code != 412) throw WebDavException(WebDavFailure.UNSUPPORTED, "服务无法防止设备互相覆盖，暂不支持双向同步")
            val second = "novelia-probe-${UUID.randomUUID()}".toByteArray()
            knownEtag = put(name, second, old)
            if(knownEtag == old) throw WebDavException(WebDavFailure.UNSUPPORTED, "服务未正确更新文件版本，暂不支持双向同步")
            val stale = request(file(name), "PUT", mapOf("If-Match" to old), "stale".toByteArray())
            if(stale.code != 412) throw WebDavException(WebDavFailure.UNSUPPORTED, "服务无法防止设备互相覆盖，暂不支持双向同步")
            val current = get(name)
            if(current == null || !current.data.contentEquals(second)) throw WebDavException(WebDavFailure.UNSUPPORTED, "服务未正确保护已有数据，暂不支持双向同步")
            requireStrongEtag(current.etag)
        } finally {
            if(created) {
                try {
                    val etag = get(name)?.etag ?: knownEtag
                    val headers = etag?.takeIf(::isStrongEtag)?.let { mapOf("If-Match" to it) }.orEmpty()
                    request(file(name), "DELETE", headers)
                } catch(cancelled: CancellationException) { throw cancelled }
                catch(_: Exception) { /* 仅留下带随机标识的探测文件，后续不遍历或删除用户文件。 */ }
            }
        }
    }

    private fun file(name: String): HttpUrl {
        require(name.length in 1..160 && name !in setOf(".", "..") && name.none { it == '/' || it == '\\' || it == '%' || it.isISOControl() }) { "同步文件名无效" }
        return directory.newBuilder().addPathSegment(name).build()
    }

    private data class Reply(val code: Int, val body: ByteArray, val etag: String?, val location: String?)

    private suspend fun request(url: HttpUrl, method: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null): Reply {
        var target = url
        repeat(4) {
            val request = Request.Builder().url(target).apply { authorization?.let { header("Authorization", it) } }
                .header("Accept", "application/json, application/xml, text/plain, */*")
                .apply { headers.forEach { (key, value) -> header(key, value) } }
                .method(method, when { body != null -> body.toRequestBody(if(method == "PROPFIND") "application/xml; charset=utf-8".toMediaType() else "application/json; charset=utf-8".toMediaType()); method == "PUT" -> ByteArray(0).toRequestBody(); else -> null }).build()
            val reply = try { client.newCall(request).awaitBody { response -> Reply(response.code, boundedBody(response), response.header("ETag"), response.header("Location")) } }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(error: WebDavException) { throw error }
            catch(_: SocketTimeoutException) { throw WebDavException(WebDavFailure.NETWORK, "连接超时，请检查网络后重试") }
            catch(_: SSLException) { throw WebDavException(WebDavFailure.NETWORK, "无法验证服务的安全连接，请检查证书和服务地址") }
            catch(_: IOException) { throw WebDavException(WebDavFailure.NETWORK, "无法连接 WebDAV 服务，请检查网络、地址及系统是否允许此连接") }
            if(reply.code !in setOf(301, 302, 307, 308)) return reply
            val next = reply.location?.let(target::resolve) ?: throw WebDavException(WebDavFailure.SERVER, "服务重定向地址无效")
            // 仅接受同源、同目录的规范化路径，凭据绝不发送到其他主机或协议。
            if(next.scheme != endpoint.scheme || next.host != endpoint.host || next.port != endpoint.port ||
                next.username.isNotEmpty() || next.password.isNotEmpty() || next.query != null || next.fragment != null ||
                next.encodedPath.trimEnd('/') != target.encodedPath.trimEnd('/')) {
                throw WebDavException(WebDavFailure.PERMISSION, "服务跳转到了不同地址，请直接填写最终的 WebDAV 服务地址")
            }
            target = next
        }
        throw WebDavException(WebDavFailure.SERVER, "服务跳转次数过多，请检查地址")
    }

    private fun boundedBody(response: Response): ByteArray {
        val body = response.body ?: return ByteArray(0)
        if(body.contentLength() > MAX_RESPONSE_BYTES) throw WebDavException(WebDavFailure.INVALID_DATA, "远端文件过大，已停止同步")
        val output = ByteArrayOutputStream()
        body.byteStream().use { input ->
            val buffer = ByteArray(8192)
            while(true) {
                val count = input.read(buffer)
                if(count < 0) break
                if(output.size() + count > MAX_RESPONSE_BYTES) throw WebDavException(WebDavFailure.INVALID_DATA, "远端文件过大，已停止同步")
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun fail(code: Int): Nothing = throw when(code) {
        401 -> WebDavException(WebDavFailure.AUTHENTICATION, "用户名或密码无效，请重新填写", code)
        403 -> WebDavException(WebDavFailure.PERMISSION, "账号没有同步目录的读写权限", code)
        404, 409 -> WebDavException(WebDavFailure.NOT_FOUND, "WebDAV 地址或父目录不存在，请检查配置", code)
        408 -> WebDavException(WebDavFailure.NETWORK, "服务等待请求超时，请稍后重试", code)
        412 -> WebDavException(WebDavFailure.CONFLICT, "远端数据已被其他设备修改，请重新同步", code)
        507 -> WebDavException(WebDavFailure.STORAGE, "WebDAV 存储空间不足", code)
        405, 501 -> WebDavException(WebDavFailure.UNSUPPORTED, "服务不支持需要的 WebDAV 操作", code)
        423 -> WebDavException(WebDavFailure.CONFLICT, "远端文件暂时被锁定，请稍后重试", code)
        429 -> WebDavException(WebDavFailure.SERVER, "服务请求过于频繁，请稍后重试", code)
        in 400..499 -> WebDavException(WebDavFailure.INVALID_DATA, "服务拒绝同步请求（$code），请检查配置或同步资料", code)
        else -> WebDavException(WebDavFailure.SERVER, "WebDAV 服务暂时不可用（$code）", code)
    }

    companion object {
        const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024
        // 仅 WebDAV 内部共享连接池，周期检查可复用 TLS 连接，仍按请求携带本次凭据。
        private val defaultTransport by lazy { OkHttpClient() }
        fun isStrongEtag(value: String): Boolean = value.length in 2..1024 && value.startsWith('"') && value.endsWith('"') &&
            value.substring(1, value.lastIndex).all { it.code in 0x21..0xff && it != '"' && it.code != 0x7f }
        fun requireStrongEtag(value: String?): String = value?.takeIf(::isStrongEtag)
            ?: throw WebDavException(WebDavFailure.UNSUPPORTED, "服务无法可靠识别文件版本，暂不支持双向同步")
        private const val PROPFIND = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>"
    }
}
