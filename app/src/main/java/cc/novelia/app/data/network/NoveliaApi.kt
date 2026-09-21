package cc.novelia.app.data.network

import cc.novelia.app.data.auth.AuthenticationSession
import cc.novelia.app.data.auth.SessionBinding
import cc.novelia.app.data.catalog.providers
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.Chapter
import cc.novelia.app.data.model.Page
import cc.novelia.app.data.model.WebDetail
import cc.novelia.app.data.model.WebOutline
import cc.novelia.app.data.model.WenkuDetail
import cc.novelia.app.data.model.WenkuOutline
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.sync.CloudMutationQueue
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

open class ApiException(val status: Int, override val message: String) : IOException(message)

/**
 * 原站 API 的统一入口，负责 URL 构造、绑定账号的认证请求、响应解码和写操作后的缓存失效。
 * 默认禁止自动跟随重定向，认证头只由此入口按捕获的会话重新设置。
 * path 接收已处理好编码的路径段；查询参数通过 HttpUrl 编码，动态路径段使用 encodeSegment。
 */
class NoveliaApi(val session: AuthenticationSession?, val baseUrl: String = "https://n.novelia.cc/api/", val transport: OkHttpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).followRedirects(false).build(), private val onMutation: (Long) -> Unit = {}, private val onKeywords: (Collection<String>) -> Unit = {}) {
    val cloudMutations = CloudMutationQueue()
    @Volatile var lastMutationAt: Long = 0L
        private set
    fun url(path: String, params: List<Pair<String, String>> = emptyList()): String = baseUrl.toHttpUrl().newBuilder().addEncodedPathSegments(path.trimStart('/')).apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build().toString()
    suspend fun request(method: String, path: String, body: String? = null, params: Map<String, String> = emptyMap(), contentType: String = "application/json", binding: SessionBinding? = session?.capture()): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url(path, params.toList())).header("Accept", "application/json")
            .method(method, if (method in listOf("GET", "HEAD")) null else (body ?: "").toRequestBody(contentType.toMediaType())).build()
        val text = withAuthenticatedResponse(request, binding) { response ->
            if (!response.isSuccessful) throw apiError(response.code)
            response.body?.string().orEmpty()
        }
        if (method !in listOf("GET", "HEAD")) recordMutation()
        text
    }
    /**
     * 请求、读取响应及 401 续期重试始终绑定同一账号和登录代次，不能中途借用新账号令牌。
     * 每轮发送前、收到响应后和返回前都校验绑定；401 最多触发一次刷新及重试。
     * [readResponse] 必须在回调内消费响应体，回调结束后底层会关闭 Response，不能向外泄漏流。
     * 网络异常直接传播；非幂等写操作是否允许重放由更上层的同步策略决定。
     */
    suspend fun <T> withAuthenticatedResponse(request: Request, binding: SessionBinding? = session?.capture(), client: OkHttpClient = transport, readResponse: (Response) -> T): T = withContext(Dispatchers.IO) {
        val bound = binding ?: session?.capture()
        fun ensureCurrent() { if (bound != null) session?.ensureCurrent(bound) }
        var retry = true
        while (true) {
            val token = bound?.let { session?.tokenFor(it) }
            val authenticated = request.newBuilder().removeHeader("Authorization").apply { token?.let { header("Authorization", "Bearer $it") } }.build()
            val result = try {
                client.newCall(authenticated).awaitBody { response ->
                    ensureCurrent()
                    if (response.code == 401 && retry) AuthResponse.Unauthorized
                    else AuthResponse.Value(readResponse(response))
                }
            } catch (error: IOException) {
                ensureCurrent()
                throw error
            }
            ensureCurrent()
            when (result) {
                is AuthResponse.Value -> return@withContext result.value
                AuthResponse.Unauthorized -> {
                    val refreshed = try { bound != null && session?.refreshIfCurrent(bound, token) == true }
                    catch (error: kotlinx.coroutines.CancellationException) { throw error }
                    catch (error: Exception) { ensureCurrent(); false }
                    ensureCurrent()
                    if (!refreshed) throw apiError(401)
                    retry = false
                }
            }
        }
        @Suppress("UNREACHABLE_CODE") error("Unreachable")
    }
    suspend inline fun <reified T> get(path: String, params: Map<String, String> = emptyMap()): T {
        val raw = request("GET", path, params = params)
        return withContext(Dispatchers.Default) { appJson.decodeFromString<T>(raw).also { observeKeywords(it) } }
    }
    /** Learn only from content already requested by the user; never fetch a global tag list. */
    fun observeKeywords(value: Any?) {
        val keywords = when(value) {
            is Page<*> -> { value.items.forEach(::observeKeywords); return }
            is WebOutline -> value.keywords
            is WebDetail -> value.keywords
            is WenkuDetail -> value.keywords
            is BookCard -> value.tags
            else -> return
        }
        if(keywords.isNotEmpty()) runCatching { onKeywords(keywords) }
    }
    suspend inline fun <reified T> put(path: String, value: T): String = request("PUT", path, appJson.encodeToString(value))
    suspend inline fun <reified T> post(path: String, value: T): String = request("POST", path, appJson.encodeToString(value))
    suspend fun webList(page: Int, query: String = "", provider: String = "", type: Int = 0, level: Int = 1, translate: Int = 0, sort: Int = 0) = get<Page<WebOutline>>("novel", mapOf("page" to "$page", "pageSize" to "20", "query" to query, "provider" to provider.ifBlank { providers.keys.joinToString(",") }, "type" to "$type", "level" to "$level", "translate" to "$translate", "sort" to "$sort"))
    suspend fun wenkuList(page: Int, query: String = "", level: Int = 0) = get<Page<WenkuOutline>>("wenku", mapOf("page" to "$page", "pageSize" to "20", "query" to query, "level" to "$level"))
    suspend fun chapter(ref: BookRef, id: String) = get<Chapter>("novel/${ref.key}/chapter/$id")
    fun downloadUrl(ref: BookRef, volume: String?, mode: String, engines: List<String>, parallel: Boolean, type: String, fileName: String): String = url(
        if (ref.isWenku) "wenku/${ref.id}/file/${encodeSegment(requireNotNull(volume))}" else "novel/${ref.key}/file",
        listOf("mode" to mode, "translationsMode" to if(parallel) "parallel" else "priority", "type" to type, "filename" to fileName) + engines.map { "translations" to it }
    )
    suspend fun uploadVolume(ref: BookRef, name: String, file: File, binding: SessionBinding? = session?.capture()) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url("wenku/${ref.id}/volume/${encodeSegment(name)}"))
            .post(MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("jp", name, file.asRequestBody("application/octet-stream".toMediaType())).build())
            .build()
        withAuthenticatedResponse(request, binding) { response ->
            if (!response.isSuccessful) throw ApiException(response.code, "上传失败（${response.code}），请检查账号权限和文件格式")
        }
        recordMutation()
    }
    /** 服务端写入已成功才推进失效时间；本地缓存通知失败不能把成功写操作伪装成失败。 */
    @Synchronized private fun recordMutation() {
        lastMutationAt = System.currentTimeMillis()
        // The remote write already succeeded; a local cache failure must not invite a duplicate post.
        runCatching { onMutation(lastMutationAt) }
    }
}
private sealed interface AuthResponse<out T> {
    data class Value<T>(val value: T) : AuthResponse<T>
    data object Unauthorized : AuthResponse<Nothing>
}
private fun apiError(code: Int) = ApiException(code, when(code) { 401 -> "请先登录，或重新登录后重试"; 403 -> "当前账号没有此操作权限，请检查账号状态与注册时间"; 404 -> "内容不存在，或尚未被网站收录"; 429 -> "请求过于频繁，请稍后重试"; else -> "服务器暂时无法完成请求（$code）" })
fun encodeSegment(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
