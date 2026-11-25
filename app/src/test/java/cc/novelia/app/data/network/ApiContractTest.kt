package cc.novelia.app.data.network

import cc.novelia.app.data.catalog.providers
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.CloudFolders
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class ApiContractTest {
    @Test fun wenkuDuplicateSearchUsesTheWebsiteKeywordAndAllPermittedLevels() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val level = request.requestUrl!!.queryParameter("level")
                    val id = if (level == "4") "non-fiction" else "same-book"
                    return MockResponse().setBody("""{"items":[{"id":"$id"}]}""")
                }
            }
            val api = NoveliaApi(null, server.url("/api/").toString())
            suspend fun check(title: String, adult: Boolean, expectedQuery: String, levels: Set<String>) {
                assertEquals(listOf("same-book", "non-fiction"), api.similarWenku(title, adult).map { it.id })
                val urls = List(levels.size) { server.takeRequest().requestUrl!! }
                assertEquals(levels, urls.map { it.queryParameter("level") }.toSet())
                assertTrue(urls.all { it.encodedPath == "/api/wenku" && it.queryParameter("query") == expectedQuery &&
                    it.queryParameter("page") == "0" && it.queryParameter("pageSize") == "6" })
            }
            check("86―小説の名！後編", true, "小説の名", setOf("0", "4", "5", "6"))
            check("  Volume 86  ", false, "Volume 86", setOf("0", "4"))
            assertEquals(6, server.requestCount)
        }
    }

    @Test fun failedWenkuLevelDoesNotReturnPartialDuplicateResults() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    if (request.requestUrl!!.queryParameter("level") == "4") MockResponse().setResponseCode(403)
                    else MockResponse().setBody("""{"items":[]}""")
            }
            val api = NoveliaApi(null, server.url("/api/").toString())
            val failure = runCatching { api.similarWenku("作品", false) }.exceptionOrNull()
            assertEquals(403, (failure as ApiException).status)
        }
    }

    @Test fun editingWebBookTextLeavesAllChapterTranslationsAlone() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse())
            val api = NoveliaApi(null, server.url("/api/").toString())
            api.updateWebBookText(BookRef("syosetu", "one"), "标题", "简介", null)
            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("/api/novel/syosetu/one/translation", request.path)
            assertEquals("""{"title":"标题","introduction":"简介","toc":{}}""", request.body.readUtf8())
        }
    }

    @Test fun guestSearchUsesAllProvidersAndGeneralAudience() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"pageNumber":1,"items":[{"providerId":"syosetu","novelId":"n1","titleJp":"Test"}]}"""))
            val api = NoveliaApi(null, server.url("/api/").toString())
            val page = api.webList(0, "中文 空格")
            val request = server.takeRequest()
            assertEquals("1", request.requestUrl!!.queryParameter("level"))
            assertEquals(providers.keys.toSet(), request.requestUrl!!.queryParameter("provider")!!.split(',').toSet())
            assertEquals("中文 空格", request.requestUrl!!.queryParameter("query"))
            assertNull(request.getHeader("Authorization"))
            assertEquals("n1", page.items.single().novelId)
        }
    }
    @Test fun downloadEscapesVolumeOnceAndKeepsRepeatedEngines() {
        val api = NoveliaApi(null)
        val value = api.downloadUrl(BookRef("wenku", "abc"), "一卷 空格.epub", "zh", listOf("sakura", "gpt"), false, "epub", "中文.epub")
        assertFalse(value.contains("%25E"))
        assertTrue(value.contains("translations=sakura&translations=gpt"))
        assertTrue(value.contains("filename="))
    }
    @Test fun unauthorizedRequestKeepsItsMeaning() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))
            val api = NoveliaApi(null, server.url("/api/").toString())
            try { api.get<CloudFolders>("user/favored"); fail("Expected authorization failure") } catch(e: ApiException) { assertEquals(401, e.status) }
        }
    }
}
