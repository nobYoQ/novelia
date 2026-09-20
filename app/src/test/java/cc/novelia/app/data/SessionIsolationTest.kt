package cc.novelia.app.data

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SessionIsolationTest {
    @Test(timeout = 10_000) fun cancellingAnUnauthorizedRequestCancelsItsRefreshWithoutRetry() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val state = SessionState("old", profile("alice"))
        val session = object : AuthenticationSession {
            override fun capture() = state.capture()
            override fun tokenFor(binding: SessionBinding) = state.tokenFor(binding)
            override suspend fun refreshIfCurrent(binding: SessionBinding, previousToken: String?): Boolean {
                try { started.complete(Unit); awaitCancellation() }
                finally { stopped.complete(Unit) }
            }
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            val api = NoveliaApi(session, server.url("/api/").toString())
            val request = async { api.request("GET", "chapter") }
            started.await()
            request.cancelAndJoin()
            stopped.await()
            assertEquals(1, server.requestCount)
            assertEquals("old", state.token)
        }
    }

    private class FakeSession(account: String? = "alice", token: String = "old") : AuthenticationSession {
        val state = SessionState(token, account?.let { profile(it) })
        var refreshes = 0
        override fun capture() = state.capture()
        override fun tokenFor(binding: SessionBinding) = state.tokenFor(binding)
        override suspend fun refreshIfCurrent(binding: SessionBinding, previousToken: String?): Boolean {
            val current = tokenFor(binding)
            if (current != previousToken) return current != null
            refreshes++
            return state.commit(binding, "refreshed", profile(requireNotNull(binding.account)), false)
        }
        fun login(account: String) { state.commit(capture(), "$account-token", profile(account), true) }
    }

    @Test fun delayedUnauthorizedCannotReplayUnderAnotherAccount() = runBlocking {
        val session = FakeSession()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    session.login("bob")
                    return MockResponse().setResponseCode(401)
                }
            }
            val api = NoveliaApi(session, server.url("/api/").toString())
            assertTrue(runCatching { api.request("PUT", "user/read-history/novel/1", "chapter-5") }.exceptionOrNull() is SessionChangedException)
            assertEquals(1, server.requestCount)
            assertEquals("Bearer old", server.takeRequest().getHeader("Authorization"))
            assertEquals(0, session.refreshes)
        }
    }

    @Test fun anonymousRequestDoesNotAcquireNewLogin() = runBlocking {
        val session = FakeSession(null)
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    session.login("alice")
                    return MockResponse().setResponseCode(401)
                }
            }
            assertTrue(runCatching { NoveliaApi(session, server.url("/").toString()).request("GET", "private") }.exceptionOrNull() is SessionChangedException)
            assertEquals(1, server.requestCount)
            assertNull(server.takeRequest().getHeader("Authorization"))
        }
    }

    @Test fun sameAccountRefreshRetriesWithRefreshedToken() = runBlocking {
        val session = FakeSession()
        val binding = session.capture()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody("ok"))
            assertEquals("ok", NoveliaApi(session, server.url("/").toString()).request("GET", "book"))
            assertEquals("Bearer old", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer refreshed", server.takeRequest().getHeader("Authorization"))
            assertEquals(binding, session.capture())
            assertEquals(1, session.refreshes)
        }
    }

    @Test fun delayedUnauthorizedCanUseAnAlreadyRefreshedTokenOfTheSameLogin() = runBlocking {
        val session = FakeSession()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.getHeader("Authorization") == "Bearer old") {
                        session.state.commit(session.capture(), "concurrently-refreshed", profile("alice"), false)
                        return MockResponse().setResponseCode(401)
                    }
                    return MockResponse().setBody("ok")
                }
            }
            assertEquals("ok", NoveliaApi(session, server.url("/").toString()).request("GET", "book"))
            assertEquals("Bearer old", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer concurrently-refreshed", server.takeRequest().getHeader("Authorization"))
            assertEquals(0, session.refreshes)
        }
    }

    @Test fun successfulResponseIsDiscardedAfterAccountSwitch() = runBlocking {
        val session = FakeSession()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    session.login("bob")
                    return MockResponse().setBody("alice-private-data")
                }
            }
            assertTrue(runCatching { NoveliaApi(session, server.url("/").toString()).request("GET", "private") }.exceptionOrNull() is SessionChangedException)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun streamingResponseUsesSameAuthenticatedRetry() = runBlocking {
        val session = FakeSession()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody("download"))
            var callbacks = 0
            val api = NoveliaApi(session, server.url("/").toString())
            val result = api.withAuthenticatedResponse(Request.Builder().url(server.url("/file")).build()) {
                callbacks++
                it.body!!.byteStream().readBytes().toString(Charsets.UTF_8)
            }
            assertEquals("download", result)
            assertEquals(1, callbacks)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun logoutInvalidatesAnInFlightRefreshBeforeItCanPersist() {
        val state = SessionState("old", profile("alice"))
        val binding = state.capture()
        val started = CountDownLatch(1)
        val finishResponse = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        var persisted = false
        try {
            val refresh = executor.submit<Boolean> {
                started.countDown()
                check(finishResponse.await(5, TimeUnit.SECONDS))
                state.commit(binding, "late-token", profile("alice"), true) { persisted = true }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            state.clear()
            finishResponse.countDown()
            assertFalse(refresh.get(5, TimeUnit.SECONDS))
            assertFalse(persisted)
            assertNull(state.token)
            assertNull(state.profile.value)
        } finally { finishResponse.countDown(); executor.shutdownNow() }
    }

    @Test fun sameNameAfterLogoutIsStillADifferentLogin() {
        val state = SessionState("old", profile("alice"))
        val binding = state.capture()
        state.clear()
        assertTrue(state.commit(state.capture(), "new", profile("alice"), true))
        assertThrows(SessionChangedException::class.java) { state.tokenFor(binding) }
        assertFalse(state.commit(binding, "late", profile("alice"), true))
        assertEquals("new", state.token)
    }

    @Test fun automaticRefreshCannotCommitAnotherAccount() {
        val state = SessionState("old", profile("alice"))
        assertFalse(state.commit(state.capture(), "bob-token", profile("bob"), false))
        assertEquals("alice", state.profile.value?.username)
        assertEquals("old", state.token)
    }

    companion object {
        private fun profile(account: String) = Profile(account, "member", 0, Long.MAX_VALUE)
    }
}
