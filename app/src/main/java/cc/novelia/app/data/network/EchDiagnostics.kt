package cc.novelia.app.data.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer

/** Anonymous, bounded probes. No business session, CookieJar or account headers are accepted. */
internal class EchDiagnostics(engine: EchEngine, private val probe: suspend (String) -> String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false).addInterceptor(EchInterceptor(engine, { true })).build()

    suspend fun run(): String = coroutineScope {
        val checks = echHosts.map { host -> async { diagnoseHost(host) } }
        checks.awaitAll().joinToString("\n\n") + "\n\n" + diagnoseForumApi()
    }

    private suspend fun diagnoseHost(host: String): String {
        val start = System.nanoTime()
        val handshake = try { probe(host) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: LinkageError) { "当前设备无法加载 ECH 本地库" }
        catch (_: Exception) { "ECH 库初始化或诊断失败" }
        currentCoroutineContext().ensureActive()
        val handshakeMillis = elapsed(start)
        val httpStart = System.nanoTime()
        var stage = "HTTP 请求失败（未收到响应头）"
        val httpResult = try {
            val request = Request.Builder().url("https://$host/cdn-cgi/trace").get().build()
            client.newCall(request).awaitBody { response ->
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
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: LinkageError) { "当前设备无法加载 ECH 本地库" }
        catch (error: EchIOException) { "$stage：${error.message}" }
        catch (_: Exception) { stage }
        return "$host\n$handshake · $handshakeMillis ms\n$httpResult · ${elapsed(httpStart)} ms"
    }

    private suspend fun diagnoseForumApi(): String {
        val reports = mutableListOf<String>()
        for ((label, url, decode) in listOf(
            ForumCheck("论坛分类 API", EchForumProbe.CATEGORIES_URL, EchForumProbe::categoryCount),
            ForumCheck("论坛帖子 API", EchForumProbe.POSTS_URL, EchForumProbe::postCount)
        )) {
            currentCoroutineContext().ensureActive()
            val start = System.nanoTime()
            var stage = "未收到响应头"
            val result = try {
                val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
                client.newCall(request).awaitBody { response ->
                    stage = "HTTP ${response.code}，正文读取失败"
                    if (!response.isSuccessful) "HTTP ${response.code}"
                    else {
                        val source = response.body?.source() ?: throw IOException("Missing body")
                        if (source.request(2L * 1024 * 1024 + 1)) throw IOException("Diagnostic response too large")
                        val json = source.readUtf8()
                        stage = "HTTP ${response.code}，JSON 解析失败"
                        "HTTP ${response.code}，读取和解析成功（${decode(json)} 项）"
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: LinkageError) { "当前设备无法加载 ECH 本地库" }
            catch (error: EchIOException) { "$stage：${error.message}" }
            catch (_: Exception) { stage }
            reports += "$label\n$result · ${elapsed(start)} ms"
        }
        return reports.joinToString("\n\n")
    }

    private data class ForumCheck(val label: String, val url: String, val decode: (String) -> Int)
    private fun elapsed(start: Long) = (System.nanoTime() - start) / 1_000_000
}
