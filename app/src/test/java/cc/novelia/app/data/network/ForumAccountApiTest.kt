package cc.novelia.app.data.network

import cc.novelia.app.data.auth.*
import cc.novelia.app.data.model.ForumSort
import cc.novelia.app.data.model.Profile
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ForumAccountApiTest {
    private class ForumTestSession : ApiSession {
        private val state = SessionState("synthetic-forum-session", Profile("测试账号", "member", 0, Long.MAX_VALUE, 42))
        override val target = AuthTarget.FORUM
        override val token get() = state.token
        override fun capture() = state.capture()
        override fun tokenFor(binding: SessionBinding) = state.tokenFor(binding)
        override suspend fun refreshIfCurrent(binding: SessionBinding, previousToken: String?) = false
        fun changeAccount() = state.replace("synthetic-next-session", Profile("另一账号", "member", 0, Long.MAX_VALUE, 43))
    }
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

    @Test fun unreadStatusAndAcknowledgementUseTheForumSessionAndExact64BitSnapshot() = runBlocking {
        MockWebServer().use { server ->
            val session = ForumTestSession()
            val client = ForumAccountApi(NoveliaApi(session, server.url("/api/v1/").toString()))
            val binding = session.capture()
            server.enqueue(MockResponse().setBody("""{"strikes":{"hasUnread":true}}"""))
            assertTrue(client.attentionStatus(binding).strikes.hasUnread)
            server.enqueue(MockResponse().setBody("""{"total":0,"items":[],"latestStrikeId":9007199254740993}"""))
            val page = client.strikes(0, binding)
            assertEquals(9007199254740993L, page.latestStrikeId)
            server.enqueue(MockResponse().setBody("""{"hasUnread":true}"""))
            assertTrue(client.markStrikesRead(page.latestStrikeId!!, binding).hasUnread)
            val requests = List(3) { server.takeRequest() }
            assertEquals("/api/v1/me/attention-status", requests[0].path)
            assertEquals("/api/v1/me/strikes?page=1&page_size=20", requests[1].path)
            assertEquals("/api/v1/me/strikes/read-state", requests[2].path)
            assertEquals("PUT", requests[2].method)
            assertEquals("""{"throughId":9007199254740993}""", requests[2].body.readUtf8())
            requests.forEach { assertEquals("Bearer synthetic-forum-session", it.getHeader("Authorization")); assertNull(it.getHeader("Cookie")) }
            assertTrue(runCatching { client.markStrikesRead(-1, binding) }.isFailure)
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun mirrorAccountApisKeepForumBearerGatewayAndExactReadSnapshot() = runBlocking {
        val sources = BookSources(BookSource.XKVI, "test-gateway-only")
        val requests = mutableListOf<Request>()
        val transport = OkHttpClient.Builder().addInterceptor(BookSourceInterceptor(sources)).addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val body = when(request.url.encodedPath) {
                "/api/v1/me/attention-status" -> """{"strikes":{"hasUnread":true}}"""
                "/api/v1/me/strikes" -> """{"total":0,"items":[],"latestStrikeId":9007199254740993}"""
                "/api/v1/me/strikes/read-state" -> """{"hasUnread":false}"""
                else -> error("Unexpected synthetic endpoint")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("test")
                .body(body.toResponseBody()).build()
        }.build()
        val api = ForumAccountApi(NoveliaApi(ForumTestSession(), ForumAccountApi.BASE_URL, transport))
        assertTrue(api.attentionStatus().strikes.hasUnread)
        val page = api.strikes(1)
        assertFalse(api.markStrikesRead(page.latestStrikeId!!).hasUnread)
        assertEquals(listOf("GET", "GET", "PUT"), requests.map { it.method })
        assertEquals(listOf("/api/v1/me/attention-status", "/api/v1/me/strikes", "/api/v1/me/strikes/read-state"),
            requests.map { it.url.encodedPath })
        assertEquals("page=2&page_size=20", requests[1].url.encodedQuery)
        val body = okio.Buffer().also { requests[2].body!!.writeTo(it) }.readUtf8()
        assertEquals("""{"throughId":9007199254740993}""", body)
        requests.forEach {
            assertEquals("book.xkvi.top", it.url.host)
            assertEquals("Bearer synthetic-forum-session", it.header("Authorization"))
            assertEquals("accessToken=test-gateway-only", it.header("Cookie"))
        }
    }

    @Test fun legacyStrikePagesDoNotInventAnAcknowledgementBoundary() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"total":0,"items":[]}"""))
            val client = ForumAccountApi(NoveliaApi(null, server.url("/").toString()))
            assertNull(client.strikes(0).latestStrikeId)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun viewingAnOldSnapshotCannotMarkTheNextAccountsStrikesRead() = runBlocking {
        MockWebServer().use { server ->
            val session = ForumTestSession()
            val client = ForumAccountApi(NoveliaApi(session, server.url("/").toString()))
            val binding = session.capture()
            server.enqueue(MockResponse().setBody("""{"total":0,"items":[],"latestStrikeId":12}"""))
            val page = client.strikes(0, binding)
            session.changeAccount()
            assertTrue(runCatching { client.markStrikesRead(page.latestStrikeId!!, binding) }.exceptionOrNull() is SessionChangedException)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun lateUnreadResponsesAreDiscardedAfterAccountChanges() = runBlocking {
        MockWebServer().use { server ->
            val received = CountDownLatch(1); val release = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    received.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    return MockResponse().setBody("""{"strikes":{"hasUnread":true}}""")
                }
            }
            val session = ForumTestSession()
            val client = ForumAccountApi(NoveliaApi(session, server.url("/").toString()))
            val pending = async(Dispatchers.Default) { runCatching { client.attentionStatus() } }
            try {
                assertTrue(received.await(5, TimeUnit.SECONDS))
                session.changeAccount()
            } finally { release.countDown() }
            assertTrue(pending.await().exceptionOrNull() is SessionChangedException)
            assertEquals(1, server.requestCount)
        }
    }
}
