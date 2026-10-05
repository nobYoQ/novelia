package cc.novelia.app.ui.community

import cc.novelia.app.data.auth.ApiSession
import cc.novelia.app.data.auth.AuthTarget
import cc.novelia.app.data.auth.SessionBinding
import cc.novelia.app.data.auth.SessionChangedException
import cc.novelia.app.data.auth.SessionState
import cc.novelia.app.data.model.Profile
import cc.novelia.app.data.network.ForumApi
import cc.novelia.app.data.network.NoveliaApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class ForumFavoriteStateTest {
    @Test fun saveAndCancelUseCloudEndpointsAndOnlySuccessfulResponsesChangeState() = runBlocking {
        MockWebServer().use { server ->
            val api = ForumApi(NoveliaApi(null, server.url("/api/v1/").toString()))
            val favorite = ForumFavoriteState(false)
            suspend fun save(value: Boolean) = favorite.setSaved(value) { api.favorite(5, it) }
            server.enqueue(MockResponse().setResponseCode(503))
            assertNotNull(runCatching { save(true) }.exceptionOrNull())
            assertFalse(favorite.saved)
            assertFalse(favorite.saving)
            server.enqueue(MockResponse().setResponseCode(204))
            save(true)
            assertTrue(favorite.saved)
            server.enqueue(MockResponse().setResponseCode(403))
            assertNotNull(runCatching { save(false) }.exceptionOrNull())
            assertTrue(favorite.saved)
            assertFalse(favorite.saving)
            server.enqueue(MockResponse().setResponseCode(204))
            save(false)
            assertFalse(favorite.saved)
            assertFalse(favorite.saving)
            assertEquals(listOf("PUT", "PUT", "DELETE", "DELETE"), (1..4).map {
                server.takeRequest().also { assertEquals("/api/v1/post/5/favorite", it.path) }.method
            })
        }
    }

    @Test fun pendingRequestBlocksRepeatedTapsAndAlreadyConfirmedValuesAreNotResent() = runBlocking {
        val favorite = ForumFavoriteState(false)
        val complete = CompletableDeferred<Unit>()
        val writes = mutableListOf<Boolean>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            favorite.setSaved(true) { writes += it; complete.await() }
        }
        assertTrue(favorite.saving)
        assertFalse(favorite.saved)
        favorite.setSaved(true) { writes += it }
        favorite.setSaved(false) { writes += it }
        assertEquals(listOf(true), writes)
        complete.complete(Unit)
        job.join()
        assertTrue(favorite.saved)
        assertFalse(favorite.saving)
        favorite.setSaved(true) { writes += it }
        assertEquals(listOf(true), writes)
    }

    @Test fun cancellationPreservesTheConfirmedFavoriteAndReenablesTheButton() = runBlocking {
        val favorite = ForumFavoriteState(true)
        assertTrue(runCatching { favorite.setSaved(false) { throw CancellationException("取消请求") } }.exceptionOrNull() is CancellationException)
        assertTrue(favorite.saved)
        assertFalse(favorite.saving)
    }

    @Test fun responseFromALoggedOutForumAccountCannotConfirmTheFavorite() = runBlocking {
        val state = SessionState("synthetic-forum-session", Profile("测试用户", "member", 0, Long.MAX_VALUE, 42))
        val session = object : ApiSession {
            override val target = AuthTarget.FORUM
            override val token get() = state.token
            override fun capture() = state.capture()
            override fun tokenFor(binding: SessionBinding) = state.tokenFor(binding)
            override suspend fun refreshIfCurrent(binding: SessionBinding, previousToken: String?) = false
        }
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    state.clear()
                    return MockResponse().setResponseCode(204)
                }
            }
            val api = ForumApi(NoveliaApi(session, server.url("/api/v1/").toString()))
            val favorite = ForumFavoriteState(false)
            assertTrue(runCatching { favorite.setSaved(true) { api.favorite(5, it) } }.exceptionOrNull() is SessionChangedException)
            assertFalse(favorite.saved)
            assertFalse(favorite.saving)
            assertEquals(1, server.requestCount)
        }
    }
}
