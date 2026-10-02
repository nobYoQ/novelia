package cc.novelia.app.data.network

import cc.novelia.app.data.storage.appJson
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request

/** Real anonymous requests, using the same ECH bridge/ALPN/retry policy as business requests. */
internal class EchDiagnostics(private val echClient: OkHttpClient, private val directClient: OkHttpClient) {
    suspend fun run(onProgress: (String, Int, Int) -> Unit = { _, _, _ -> }): String = coroutineScope {
        val run = UUID.randomUUID().toString()
        val results = mutableListOf<String>()
        var completed = 0
        val total = checks.size * 2
        val explanation = "直连与 ECH 分别发送匿名请求，不改变 ECH 开关。新连接与后续复用连接可能表现不同；HTTP 状态错误与 JSON 格式错误不等同于网络不通。"
        fun report() = explanation + "\n\n" + results.joinToString("\n\n")
        onProgress(report(), completed, total)
        // At most two requests, one per route, at a time. Avoid flooding slow DoH resolvers.
        for (check in checks) {
            listOf("direct" to directClient, "ECH" to echClient).map { (route, client) ->
                async {
                    val text = check(client, check, route, run)
                    synchronized(results) {
                        results += text
                        completed++
                        onProgress(report(), completed, total)
                    }
                }
            }.awaitAll()
        }
        report()
    }

    private suspend fun check(client: OkHttpClient, check: Check, route: String, run: String): String {
        val start = System.nanoTime()
        var stage = "连接或等待响应头"
        val result = try {
            val request = Request.Builder().url(check.url).header("Accept", if (check.parse != null) "application/json" else "text/plain")
                .tag(DiagnosticRequestTag::class.java, DiagnosticRequestTag(run, route)).get().build()
            client.newCall(request).awaitBody { response ->
                stage = "HTTP ${response.code}，读取响应正文"
                val source = response.body?.source() ?: throw IOException("Missing body")
                if (source.request(MAX_BODY_BYTES + 1)) return@awaitBody "HTTP ${response.code}，响应超过检测大小限制"
                val bytes = source.readByteArray()
                if (!response.isSuccessful) "HTTP ${response.code}，连接及正文读取成功，接口返回非成功状态"
                else {
                    stage = "HTTP ${response.code}，JSON 解析"
                    val parsed = check.parse?.let { parser ->
                        try { parser(bytes.toString(Charsets.UTF_8)) }
                        catch (_: Exception) { return@awaitBody "HTTP ${response.code}，正文已读完，JSON 格式不符合预期" }
                    }
                    "HTTP ${response.code} · ${response.protocol} · 正文读取完整（${bytes.size} 字节）" + (parsed?.let { " · $it" } ?: "")
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: LinkageError) { "$stage 失败：本地 ECH 库不可用" }
        catch (error: Exception) { "$stage 失败：${networkFailureLabel(networkFailure(error))}（详情见阶段日志）" }
        val label = if (route == "direct") "直连" else "ECH"
        return "${check.label} · $label\n$result · ${(System.nanoTime() - start) / 1_000_000} ms"
    }

    private data class Check(val label: String, val url: String, val parse: ((String) -> String)? = null)
    companion object {
        private const val MAX_BODY_BYTES = 2L * 1024 * 1024
        private val checks = listOf(
            Check("小说连接（新连接）", "https://n.novelia.cc/cdn-cgi/trace"),
            Check("网络小说列表", "https://n.novelia.cc/api/novel?page=0&pageSize=1&provider=kakuyomu&type=0&level=1&translate=0&sort=0&query=", ::validateJson),
            Check("文库列表", "https://n.novelia.cc/api/wenku?page=0&pageSize=1", ::validateJson),
            Check("认证连接（新连接）", "https://auth.novelia.cc/cdn-cgi/trace"),
            Check("论坛连接（新连接）", "https://forum.novelia.cc/cdn-cgi/trace"),
            Check("论坛分类 API", EchForumProbe.CATEGORIES_URL) { "分类 ${EchForumProbe.categoryCount(it)} 项" },
            Check("论坛帖子 API", EchForumProbe.POSTS_URL) { "帖子 ${EchForumProbe.postCount(it)} 项" }
        )
        private fun validateJson(text: String): String { appJson.parseToJsonElement(text); return "JSON 解析成功" }
    }
}

internal fun networkFailureLabel(reason: String) = when (reason) {
    "timeout" -> "超时"; "cancelled" -> "已取消"; "dns" -> "域名解析失败"
    "certificate" -> "证书验证失败"; "tls" -> "TLS 协商失败"; "connect" -> "连接建立失败"
    "reset" -> "连接被重置"; "unreachable" -> "网络不可达"; "native_library" -> "本地 ECH 库不可用"
    "ech" -> "ECH 请求失败"; "io" -> "网络读写失败"; else -> "检测异常"
}
