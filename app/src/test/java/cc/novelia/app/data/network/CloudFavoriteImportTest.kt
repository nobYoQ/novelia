package cc.novelia.app.data.network

import cc.novelia.app.data.auth.AuthenticationSession
import cc.novelia.app.data.auth.SessionBinding
import cc.novelia.app.data.auth.SessionChangedException
import cc.novelia.app.data.catalog.providers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class CloudFavoriteImportTest {
    @Test fun webImportReadsAllPagesAndFoldersWithoutFiltersAndDeduplicatesByFullRef() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"pageNumber":3,"items":[{"providerId":"syosetu","novelId":"1","favored":"default"}]}"""))
            server.enqueue(MockResponse().setBody("""{"pageNumber":3,"items":[{"providerId":"hameln","novelId":"1","favored":"reading"},{"providerId":"syosetu","novelId":"1"}]}"""))
            server.enqueue(MockResponse().setBody("""{"pageNumber":3,"items":[{"providerId":"pixiv","novelId":"2","favored":"other"}]}"""))
            val progress = mutableListOf<CloudFavoriteImportProgress>()
            val books = NoveliaApi(null, server.url("/api/").toString()).allCloudFavorites(false, onProgress = progress::add)
            assertEquals(listOf("syosetu/1", "hameln/1", "pixiv/2"), books.map { it.ref.key })
            assertEquals(listOf(CloudFavoriteImportProgress(1, 3, 1), CloudFavoriteImportProgress(2, 3, 2), CloudFavoriteImportProgress(3, 3, 3)), progress)
            repeat(3) { page ->
                val url = server.takeRequest().requestUrl!!
                assertEquals("/api/user/favored-web/all", url.encodedPath)
                mapOf("page" to "$page", "pageSize" to "20", "sort" to "create", "query" to "",
                    "provider" to providers.keys.joinToString(","), "type" to "0", "level" to "0", "translate" to "0")
                    .forEach { (key, value) -> assertEquals(key, value, url.queryParameter(key)) }
            }
        }
    }

    @Test fun wenkuImportUsesOnlyWenkuPagesAndNeverSendsWebFilters() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"pageNumber":2,"items":[{"id":"w1","title":"文库一"}]}"""))
            server.enqueue(MockResponse().setBody("""{"pageNumber":2,"items":[{"id":"w2","title":"文库二"}]}"""))
            val books = NoveliaApi(null, server.url("/api/").toString()).allCloudFavorites(true)
            assertEquals(listOf("wenku/w1", "wenku/w2"), books.map { it.ref.key })
            repeat(2) { page ->
                val url = server.takeRequest().requestUrl!!
                assertEquals("/api/user/favored-wenku/all", url.encodedPath)
                assertEquals("$page", url.queryParameter("page"))
                assertEquals(setOf("page", "pageSize", "sort"), url.queryParameterNames)
            }
        }
    }

    @Test fun emptyFavoritesFinishAfterTheFirstPage() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"pageNumber":0,"items":[]}"""))
            assertTrue(NoveliaApi(null, server.url("/api/").toString()).allCloudFavorites(false).isEmpty())
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun laterPageFailureDoesNotReturnAPartialImport() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"pageNumber":2,"items":[{"providerId":"syosetu","novelId":"1"}]}"""))
            server.enqueue(MockResponse().setResponseCode(403))
            try {
                NoveliaApi(null, server.url("/api/").toString()).allCloudFavorites(false)
                fail("读取失败时不能提交已读取的部分书目")
            } catch(expected: ApiException) { assertEquals(403, expected.status) }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun sessionChangeAfterAPageStopsBeforeSendingTheNextRequest() = runBlocking {
        var binding = SessionBinding("alice", 1)
        val session = object : AuthenticationSession {
            override fun capture() = binding
            override fun tokenFor(binding: SessionBinding): String? {
                if(binding != capture()) throw SessionChangedException()
                return null
            }
            override suspend fun refreshIfCurrent(binding: SessionBinding, previousToken: String?) = false
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"pageNumber":2,"items":[{"providerId":"syosetu","novelId":"1"}]}"""))
            try {
                NoveliaApi(session, server.url("/api/").toString()).allCloudFavorites(false) { binding = SessionBinding("bob", 2) }
                fail("切换会话必须中止导入")
            } catch(expected: SessionChangedException) { assertEquals(1, server.requestCount) }
        }
    }

    @Test fun sessionChangeOnTheLastPageStillRejectsTheImport() = runBlocking {
        var current = true
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"pageNumber":1,"items":[]}"""))
            try {
                NoveliaApi(null, server.url("/api/").toString()).allCloudFavorites(true,
                    validate = { if(!current) throw SessionChangedException() }) { current = false }
                fail("最后一页后也必须校验会话")
            } catch(expected: SessionChangedException) { assertEquals(1, server.requestCount) }
        }
    }

    @Test fun cancellationStopsPaginationAndPropagatesToTheCaller() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"pageNumber":2,"items":[]}"""))
            try {
                NoveliaApi(null, server.url("/api/").toString()).allCloudFavorites(false) { throw CancellationException("cancelled") }
                fail("取消导入必须继续传播")
            } catch(expected: CancellationException) { assertEquals(1, server.requestCount) }
        }
    }
}
