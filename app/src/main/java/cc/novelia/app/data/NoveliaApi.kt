package cc.novelia.app.data

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
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, override val message: String) : IOException(message)

class NoveliaApi(val session: Session?, val baseUrl: String = "https://n.novelia.cc/api/", val transport: OkHttpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).followRedirects(false).build(), private val onMutation: (Long) -> Unit = {}) {
    @Volatile var lastMutationAt: Long = 0L
        private set
    fun url(path: String, params: List<Pair<String, String>> = emptyList()): String = baseUrl.toHttpUrl().newBuilder().addEncodedPathSegments(path.trimStart('/')).apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build().toString()
    suspend fun request(method: String, path: String, body: String? = null, params: Map<String, String> = emptyMap(), contentType: String = "application/json"): String = withContext(Dispatchers.IO) {
        suspend fun execute(retry: Boolean): String {
            val token = session?.token
            val request = Request.Builder().url(url(path, params.toList())).header("Accept", "application/json").apply {
                token?.let { header("Authorization", "Bearer $it") }
                method(method, if (method in listOf("GET", "HEAD")) null else (body ?: "").toRequestBody(contentType.toMediaType()))
            }.build()
            val response = transport.newCall(request).awaitText()
            if (response.code == 401 && retry && runCatching { session?.refreshIfCurrent(token) }.getOrDefault(false) == true) return execute(false)
            if (response.code !in 200..299) throw ApiException(response.code, when(response.code) { 401 -> "请先登录，或重新登录后重试"; 403 -> "当前账号没有此操作权限，请检查账号状态与注册时间"; 404 -> "内容不存在，或尚未被网站收录"; 429 -> "请求过于频繁，请稍后重试"; else -> "服务器暂时无法完成请求（${response.code}）" })
            if (method !in listOf("GET", "HEAD")) recordMutation()
            return response.text
        }
        execute(true)
    }
    suspend inline fun <reified T> get(path: String, params: Map<String, String> = emptyMap()): T {
        val raw = request("GET", path, params = params)
        return withContext(Dispatchers.Default) { appJson.decodeFromString<T>(raw) }
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
    suspend fun uploadVolume(ref: BookRef, name: String, file: File) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url("wenku/${ref.id}/volume/${encodeSegment(name)}"))
            .post(MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("jp", name, file.asRequestBody("application/octet-stream".toMediaType())).build())
            .apply { session?.token?.let { header("Authorization", "Bearer $it") } }.build()
        val response = transport.newCall(request).awaitText()
        if (response.code !in 200..299) throw ApiException(response.code, "上传失败（${response.code}），请检查账号权限和文件格式")
        recordMutation()
    }
    @Synchronized private fun recordMutation() {
        lastMutationAt = System.currentTimeMillis()
        // The remote write already succeeded; a local cache failure must not invite a duplicate post.
        runCatching { onMutation(lastMutationAt) }
    }
}
fun encodeSegment(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
