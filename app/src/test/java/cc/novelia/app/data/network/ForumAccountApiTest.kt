package cc.novelia.app.data.network

import cc.novelia.app.data.auth.*
import cc.novelia.app.data.model.ForumSort
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ForumAccountApiTest {
    @Test fun allFourPresetsSendTheServerSortValueAndPreserveFilters() = runBlocking {
        MockWebServer().use { server ->
            val api = ForumApi(NoveliaApi(null, server.url("/api/v1/").toString()))
            assertEquals(listOf("最近活跃", "最新发布", "浏览最多", "评论最多"), ForumSort.entries.map { it.label })
            for(sort in ForumSort.entries) {
                server.enqueue(MockResponse().setBody("""{"total":0,"items":[]}"""))
                api.posts(0, "guide", "检索", sort.apiValue)
                val url = server.takeRequest().requestUrl!!
                assertEquals(sort.apiValue, url.queryParameter("sort"))
                assertEquals("guide", url.queryParameter("category"))
                assertEquals("检索", url.queryParameter("q"))
                assertEquals("1", url.queryParameter("page"))
            }
        }
    }

    @Test fun strikesUseAuthServiceAndForumTokenWithRevocationAndPaging() = runBlocking {
        MockWebServer().use { forum -> MockWebServer().use { auth ->
            val session = object : ApiSession {
                override val target = AuthTarget.FORUM
                override val token = "synthetic-forum-session"
                override fun capture() = SessionBinding("forum-test", 0)
                override fun tokenFor(binding: SessionBinding) = token.also { require(binding == capture()) }
                override suspend fun refreshIfCurrent(binding: SessionBinding, previousToken: String?) = false
            }
            val api = ForumAccountApi(NoveliaApi(session, auth.url("/api/v1/").toString()))
            auth.enqueue(MockResponse().setBody("""{"total":21,"items":[{"id":1,"reason":"版规提醒","evidence":"具体依据","point":2,"createdAt":"2026-09-15T00:00:00Z"},{"id":2,"reason":"已撤销的记录","evidence":"","point":1,"createdAt":"2026-09-14T00:00:00Z","revokedAt":"2026-09-15T08:00:00+08:00"}]}"""))
            val page = api.strikes(1)
            val request = auth.takeRequest()
            assertEquals("/api/v1/me/strikes?page=2&page_size=20", request.path)
            assertEquals("Bearer synthetic-forum-session", request.getHeader("Authorization"))
            assertNull(request.getHeader("Cookie"))
            assertEquals(0, forum.requestCount)
            assertEquals(2, page.pageCount())
            assertNull(page.items.first().revokedEpoch)
            assertEquals(page.items.first().createdEpoch, page.items.last().revokedEpoch)
            assertEquals("具体依据", page.items.first().evidence)
            assertEquals(2, page.items.first().point)
        } }
    }

    @Test fun strikesRejectMainSessionAndKeepUnauthorizedResponse() = runBlocking {
        MockWebServer().use { server ->
            val main = object : ApiSession {
                override val target = AuthTarget.NOVEL
                override val token = "synthetic-main-session"
                override fun capture() = SessionBinding("main-test", 0)
                override fun tokenFor(binding: SessionBinding) = token.also { require(binding == capture()) }
                override suspend fun refreshIfCurrent(binding: SessionBinding, previousToken: String?) = false
            }
            assertTrue(runCatching { ForumAccountApi(NoveliaApi(main, server.url("/").toString())) }.isFailure)
            server.enqueue(MockResponse().setResponseCode(401))
            val api = ForumAccountApi(NoveliaApi(null, server.url("/").toString()))
            assertEquals(401, (runCatching { api.strikes(0) }.exceptionOrNull() as ApiException).status)
            assertEquals(1, server.requestCount)
        }
    }
}
