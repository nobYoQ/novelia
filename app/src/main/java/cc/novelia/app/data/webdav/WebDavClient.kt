package cc.novelia.app.data.webdav

import cc.novelia.app.data.network.awaitBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.SocketTimeoutException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
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

enum class WebDavFailure { AUTHENTICATION, PERMISSION, NOT_FOUND, CONFLICT, STORAGE, SERVER, RATE_LIMITED, NETWORK, INSECURE, UNSUPPORTED, INVALID_DATA }

class WebDavException(val failure: WebDavFailure, message: String, val statusCode: Int? = null,
    val retryAfterMillis: Long? = null) : IOException(message)

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
class WebDavClient(config: WebDavConfig, password: String, transport: OkHttpClient? = null,
    private val beforeRequest: () -> Unit = {}, private val onRateLimit: (WebDavException) -> Unit = {}) {
    private val endpoint = WebDavPaths.endpoint(config)
    // 坚果云不可靠地执行 PUT If-None-Match:*；首次创建改用禁止覆盖的原子 MOVE。
    private val isNutcloud = endpoint.host == "dav.jianguoyun.com"
    private val folderSegments = WebDavPaths.folderSegments(config.folder)
    private val directory = folderSegments.fold(endpoint) { url, segment -> url.newBuilder().addPathSegment(segment).build() }
        .newBuilder().addPathSegment("").build()
    private val authorization = if(config.username.isEmpty() && password.isEmpty()) null else Credentials.basic(config.username, password, Charsets.UTF_8)
    private var rateLimitError: WebDavException? = null
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
                403, 404, 405, 409 -> {
                    // 部分服务对已存在目录返回 403/409；以目标资源的成功属性核验，兼容并发创建。
                    val existing = request(parent, "PROPFIND", headers = mapOf("Depth" to "0"), body = PROPFIND.toByteArray())
                    if(existing.code == 404) fail(response.code, "MKCOL")
                    if(existing.code != 207 && existing.code != 200) fail(existing.code, "PROPFIND")
                    if(!isWebDavCollection(existing.body, parent)) {
                        throw WebDavException(WebDavFailure.UNSUPPORTED, "同步目录不是有效的 WebDAV 文件夹")
                    }
                }
                else -> fail(response.code, "MKCOL")
            }
        }
    }

    suspend fun get(name: String, etag: String? = null): WebDavResource? {
        val headers = if(etag == null) emptyMap() else mapOf("If-None-Match" to conditionalEtag(etag))
        val response = request(file(name), "GET", headers)
        return when(response.code) {
            200 -> if(response.etag != null) WebDavResource(response.body, response.etag) else readWithPropertyVersion(name)
            304 -> WebDavResource(ByteArray(0), response.etag ?: etag, true)
            404 -> null
            else -> fail(response.code, "GET")
        }
    }

    /** 所有上传都需指定已有版本或明确只创建新文件。 */
    suspend fun put(name: String, data: ByteArray, etag: String? = null, createOnly: Boolean = false): String {
        require(data.size <= MAX_RESPONSE_BYTES) { "同步数据过大" }
        require(createOnly.xor(etag != null)) { "上传需要指定当前版本或仅创建新文件" }
        if(createOnly && isNutcloud) return createByMove(name, data)
        val headers = if(createOnly) mapOf("If-None-Match" to "*") else mapOf("If-Match" to conditionalEtag(etag!!))
        val response = request(file(name), "PUT", headers, data)
        if(response.code !in setOf(200, 201, 204)) fail(response.code, "PUT")
        val returned = response.etag
        if(returned != null) return requireStrongEtag(returned)
        // 部分 WebDAV 服务不在 PUT 返回版本，读取并核对后再确认本次上传。
        val saved = get(name) ?: throw WebDavException(WebDavFailure.INVALID_DATA, "上传后的文件无法读取，请重试")
        if(!saved.data.contentEquals(data)) throw WebDavException(WebDavFailure.CONFLICT, "远端数据已被其他设备修改，请重试")
        return requireStrongEtag(saved.etag)
    }

    /** 属性版本与旧 GET 内容不能直接配对；按属性版本重新条件读取，并验证服务执行读条件。 */
    private suspend fun readWithPropertyVersion(name: String): WebDavResource {
        val target = file(name)
        val properties = request(target, "PROPFIND", mapOf("Depth" to "0"), ETAG_PROPFIND.toByteArray(Charsets.UTF_8))
        if(properties.code == 404) throw WebDavException(WebDavFailure.CONFLICT, "读取期间远端文件已变化，请重试")
        if(properties.code !in setOf(200, 207)) fail(properties.code, "PROPFIND")
        val version = normalizeReturnedEtag(readWebDavPropertyEtag(properties.body, target))
            ?: throw WebDavException(WebDavFailure.UNSUPPORTED, "当前服务未返回可用于防覆盖的文件版本，请检查 WebDAV 服务兼容性")
        val checked = request(target, "GET", mapOf("If-Match" to conditionalEtag(version)))
        if(checked.code == 404) throw WebDavException(WebDavFailure.CONFLICT, "读取期间远端文件已变化，请重试", 404)
        if(checked.code != 200) fail(checked.code, "GET")
        if(checked.etag != null && checked.etag != version) throw WebDavException(WebDavFailure.CONFLICT, "读取期间远端文件版本已变化，请重试")
        // 此只读检查不改变文件；若条件被忽略，无法证明正文属于取得的属性版本。
        val rejected = request(target, "GET", mapOf("If-Match" to conditionalEtag("\"novelia-absent-${UUID.randomUUID()}\"")))
        if(rejected.code != 412) {
            if(rejected.code == 404) throw WebDavException(WebDavFailure.CONFLICT, "读取期间远端文件已变化，请重试", 404)
            if(rejected.code !in setOf(200, 304)) fail(rejected.code, "GET")
            throw WebDavException(WebDavFailure.UNSUPPORTED, "当前服务无法按文件版本安全读取，已保留本机资料")
        }
        return WebDavResource(checked.body, version)
    }

    /** 随机临时文件只属于本次操作，目标始终使用 Overwrite:F，避免空目录下两台设备相互覆盖。 */
    private suspend fun createByMove(name: String, data: ByteArray, onCreated: () -> Unit = {}): String {
        // 与常用 WebDAV 客户端一致，使用非隐藏的普通暂存文件；不使用 .tmp 后缀。
        val temporary = file("novelia-upload-${UUID.randomUUID()}.cache")
        var created = false
        try {
            // UUID 暂存文件没有共享写者；禁止覆盖的检查放在提交共享目标的 MOVE 上。
            val uploaded = request(temporary, "PUT", body = data)
            if(uploaded.code !in setOf(200, 201, 204)) fail(uploaded.code, "PUT")
            created = true
            val destination = file(name)
            var moved = moveWithoutOverwrite(temporary, destination, destination.toString())
            if(moved.code == 409) {
                verifyTemporarySource(temporary, data)
                // 参照 Kazumi 所用客户端：MOVE 409 时补建父目录，再重试同一目标。
                // 只重试一次，不采用其递归重试或删除已有目标的提交方式。
                ensureDirectory()
                moved = moveWithoutOverwrite(temporary, destination, destination.toString())
            }
            if(moved.code in setOf(404, 409)) {
                // 兼容反向代理无法映射完整 Destination URL 的服务；仅对仍属于本次操作的源文件重试。
                verifyTemporarySource(temporary, data)
                // RFC 4918 允许 absolute-path；目标和禁止覆盖条件不变，也不降级为 PUT。
                moved = moveWithoutOverwrite(temporary, destination, destination.encodedPath)
            }
            if(moved.code !in setOf(200, 201, 204)) fail(moved.code, "MOVE")
            onCreated()
            val saved = get(name) ?: throw WebDavException(WebDavFailure.INVALID_DATA, "创建后的同步文件无法读取，请重试")
            if(!saved.data.contentEquals(data)) throw WebDavException(WebDavFailure.CONFLICT, "远端数据已被其他设备修改，请重试")
            return requireStrongEtag(saved.etag)
        } finally {
            if(created) try { request(temporary, "DELETE") }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { /* 只可能留下本次随机临时文件，不遍历其他文件。 */ }
        }
    }

    private suspend fun moveWithoutOverwrite(source: HttpUrl, destination: HttpUrl, destinationHeader: String): Reply {
        val moved = request(source, "MOVE", mapOf("Destination" to destinationHeader, "Overwrite" to "F"))
        if(moved.code == 409) {
            // 坚果云实测：目标已存在时返回 409，而不是 RFC 规定的 412。
            // 只在核验确切目标存在后识别为并发冲突；真正缺少父目录仍走恢复分支。
            val existing = request(destination, "GET")
            if(existing.code == 200) throw WebDavException(WebDavFailure.CONFLICT, "同步文件已存在，请读取远端最新资料后重新合并", 409)
            if(existing.code != 404) fail(existing.code, "GET")
        }
        return moved
    }

    private suspend fun verifyTemporarySource(temporary: HttpUrl, data: ByteArray) {
        val source = request(temporary, "GET")
        if(source.code != 200) fail(source.code, "GET")
        if(!source.body.contentEquals(data)) throw WebDavException(WebDavFailure.CONFLICT, "临时上传文件已变化，已停止提交")
    }

    /** 唯一临时文件检测实际读写和并发保护；只清理本次成功创建的文件。 */
    suspend fun testConnection() {
        ensureDirectory()
        val name = "novelia-probe-${UUID.randomUUID()}.txt"
        var created = false
        var knownEtag: String? = null
        try {
            val first = "novelia-probe-${UUID.randomUUID()}".toByteArray()
            if(isNutcloud) {
                // 仅在目标创建成功后取得清理权，读版本失败时也能清理。
                createByMove(name, first) { created = true }
            } else {
                val create = request(file(name), "PUT", mapOf("If-None-Match" to "*"), first)
                if(create.code !in setOf(200, 201, 204)) fail(create.code, "PUT")
            }
            created = true
            val read = get(name) ?: throw WebDavException(WebDavFailure.UNSUPPORTED, "服务无法读取刚创建的测试文件")
            if(!read.data.contentEquals(first)) throw WebDavException(WebDavFailure.UNSUPPORTED, "服务返回的测试内容不一致")
            val old = requireStrongEtag(read.etag)
            knownEtag = old
            if(isNutcloud) {
                try {
                    createByMove(name, "duplicate".toByteArray())
                    throw WebDavException(WebDavFailure.UNSUPPORTED, "当前服务未执行禁止覆盖的文件创建，已停止同步")
                } catch(error: WebDavException) {
                    if(error.failure != WebDavFailure.CONFLICT || error.statusCode !in setOf(409, 412)) throw error
                }
            } else {
                val duplicate = request(file(name), "PUT", mapOf("If-None-Match" to "*"), "duplicate".toByteArray())
                if(duplicate.code != 412) throw WebDavException(WebDavFailure.UNSUPPORTED, "当前服务未执行禁止覆盖的文件创建，已停止同步")
            }
            // 拒绝状态不足以证明没有覆盖：包括坚果云非标准 409，均需读回原正文和版本。
            val unchanged = get(name)
            if(unchanged == null || !unchanged.data.contentEquals(first) || unchanged.etag != old) {
                throw WebDavException(WebDavFailure.UNSUPPORTED, "当前服务在拒绝重复创建时仍改变了原文件，已停止同步")
            }
            val second = "novelia-probe-${UUID.randomUUID()}".toByteArray()
            knownEtag = put(name, second, old)
            if(knownEtag == old) throw WebDavException(WebDavFailure.UNSUPPORTED, "当前服务更新文件后未更新版本标识，已停止同步")
            val stale = request(file(name), "PUT", mapOf("If-Match" to conditionalEtag(old)), "stale".toByteArray())
            if(stale.code != 412) throw WebDavException(WebDavFailure.UNSUPPORTED, "当前服务未阻止过期版本覆盖，已停止同步")
            val current = get(name)
            if(current == null || !current.data.contentEquals(second)) throw WebDavException(WebDavFailure.UNSUPPORTED, "服务未正确保护已有数据，暂不支持双向同步")
            requireStrongEtag(current.etag)
        } finally {
            if(created) {
                try {
                    val etag = try { get(name)?.etag ?: knownEtag }
                    catch(cancelled: CancellationException) { throw cancelled }
                    catch(_: Exception) { knownEtag }
                    val headers = etag?.takeIf(::isStrongEtag)?.let { mapOf("If-Match" to conditionalEtag(it)) }.orEmpty()
                    request(file(name), "DELETE", headers)
                } catch(cancelled: CancellationException) { throw cancelled }
                catch(_: Exception) { /* 仅留下带随机标识的探测文件，后续不遍历或删除用户文件。 */ }
            }
        }
    }

    /** 内部版本保持标准强 ETag；仅坚果云官方端点按其实测的裸值条件格式发送。 */
    private fun conditionalEtag(value: String): String {
        val strong = requireStrongEtag(value)
        return if(isNutcloud) strong.substring(1, strong.lastIndex) else strong
    }

    private fun file(name: String): HttpUrl {
        require(name.length in 1..160 && name !in setOf(".", "..") && name.none { it == '/' || it == '\\' || it == '%' || it.isISOControl() }) { "同步文件名无效" }
        return directory.newBuilder().addPathSegment(name).build()
    }

    private data class Reply(val code: Int, val body: ByteArray, val etag: String?, val location: String?)

    private suspend fun request(url: HttpUrl, method: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null): Reply {
        var target = url
        repeat(4) {
            // 同一轮的后续类型和 finally 清理都停止发请求；下轮也受 manager 的账号冷却约束。
            rateLimitError?.let { throw it }
            beforeRequest()
            val request = Request.Builder().url(target).apply { authorization?.let { header("Authorization", it) } }
                .header("Accept", "application/json, application/xml, text/plain, */*")
                // 避免中间服务压缩响应时将原文件的强版本降为弱版本。
                .header("Accept-Encoding", "identity")
                .apply { headers.forEach { (key, value) -> header(key, value) } }
                .method(method, when { body != null -> body.toRequestBody(if(method == "PROPFIND") "application/xml; charset=utf-8".toMediaType() else "application/octet-stream".toMediaType()); method == "PUT" -> ByteArray(0).toRequestBody(); else -> null }).build()
            val reply = try { client.newCall(request).awaitBody { response ->
                val data = boundedBody(response)
                val limited = response.code == 429 || response.code == 503 &&
                    data.toString(Charsets.UTF_8).let {
                        it.contains("too many requests", ignoreCase = true) || it.contains("rate limit", ignoreCase = true)
                    }
                if(limited) {
                    val wait = retryAfterMillis(response.header("Retry-After"))?.coerceAtLeast(1_000L) ?: 30 * 60_000L
                    val error = WebDavException(WebDavFailure.RATE_LIMITED,
                        "WebDAV 服务请求过于频繁（${response.code}），已暂停请求，请稍后重试", response.code, wait)
                    rateLimitError = error
                    onRateLimit(error)
                    throw error
                }
                Reply(response.code, data, normalizeReturnedEtag(response.header("ETag")), response.header("Location"))
            } }
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

    private fun fail(code: Int, method: String): Nothing {
        val error = when(code) {
            401 -> WebDavException(WebDavFailure.AUTHENTICATION, "用户名或密码无效，请重新填写", code)
            403 -> WebDavException(WebDavFailure.PERMISSION, "账号没有同步目录的读写权限", code)
            404, 409 -> WebDavException(WebDavFailure.NOT_FOUND, when(method) {
                "MKCOL" -> "服务未找到基础目录，或不允许在此创建同步目录，请检查服务器地址"
                "PUT" -> "服务未找到上传目录，或不允许在该路径写入文件"
                "MOVE" -> "服务未能定位移动的源文件或目标位置；这不一定表示同步目录不存在"
                "PROPFIND" -> "服务未找到要查询的文件或目录"
                else -> "服务未找到要读取的文件，文件可能已被移动或删除"
            }, code)
            408 -> WebDavException(WebDavFailure.NETWORK, "服务等待请求超时，请稍后重试", code)
            412 -> WebDavException(WebDavFailure.CONFLICT, "远端数据已被其他设备修改，请重新同步", code)
            507 -> WebDavException(WebDavFailure.STORAGE, "WebDAV 存储空间不足", code)
            405, 501 -> WebDavException(WebDavFailure.UNSUPPORTED, "服务不支持需要的 WebDAV 操作", code)
            423 -> WebDavException(WebDavFailure.CONFLICT, "远端文件暂时被锁定，请稍后重试", code)
            429 -> WebDavException(WebDavFailure.SERVER, "服务请求过于频繁，请稍后重试", code)
            in 400..499 -> WebDavException(WebDavFailure.INVALID_DATA, "服务拒绝同步请求（$code），请检查配置或同步资料", code)
            else -> WebDavException(WebDavFailure.SERVER, "WebDAV 服务暂时不可用（$code）", code)
        }
        val operation = when(method) {
            "MKCOL" -> "创建同步目录"
            "PROPFIND" -> "查询远端属性"
            "PUT" -> "上传同步文件"
            "MOVE" -> "移动同步文件"
            else -> "读取同步文件"
        }
        throw WebDavException(error.failure, "$operation 失败（$method $code）：${error.message}", code)
    }

    companion object {
        const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024
        // 仅 WebDAV 内部共享连接池，周期检查可复用 TLS 连接，仍按请求携带本次凭据。
        private val defaultTransport by lazy { OkHttpClient() }
        internal fun retryAfterMillis(value: String?, now: Long = System.currentTimeMillis()): Long? {
            val text = value?.trim() ?: return null
            text.toLongOrNull()?.takeIf { it >= 0 }?.let { return it.coerceAtMost(Long.MAX_VALUE / 1_000) * 1_000 }
            return runCatching {
                (ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now).coerceAtLeast(0)
            }.getOrNull()
        }
        fun isStrongEtag(value: String): Boolean = value.length in 2..1024 && value.startsWith('"') && value.endsWith('"') &&
            value.substring(1, value.lastIndex).all { it.code in 0x21..0xff && it != '"' && it.code != 0x7f }
        fun requireStrongEtag(value: String?): String = value?.takeIf(::isStrongEtag)
            ?: throw WebDavException(WebDavFailure.UNSUPPORTED, "当前服务未返回可用于防覆盖的文件版本，请检查 WebDAV 服务兼容性")
        /** 兼容服务返回的裸标识；不将 W/ 弱标识升级，也不接纳多个值或控制字符。 */
        internal fun normalizeReturnedEtag(value: String?): String? {
            if(value?.any { it.isISOControl() } == true) return null
            val tag = value?.trim() ?: return null
            if(isStrongEtag(tag)) return tag
            if(tag.isEmpty() || tag.length > 1022 || tag.startsWith("W/", ignoreCase = true) ||
                tag.any { !it.isLetterOrDigit() && it !in "._:=+-/" || it.code !in 0x21..0x7e }) return null
            return "\"$tag\""
        }
        private const val PROPFIND = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>"
        private const val ETAG_PROPFIND = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:getetag/></d:prop></d:propfind>"
    }
}
