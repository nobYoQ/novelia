package cc.novelia.app.data.network

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class NetworkPerformanceTest {
    @Test(timeout = 15_000) fun changingDownloadLimitPromotesQueuedCallsAndLetsActiveCallsFinish() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) }
            server.enqueue(MockResponse().setChunkedBody("book", 2))
            val client = OkHttpClient().forDownloads()
            client.dispatcher.setDownloadConcurrency(1)
            val queued = List(3) { CompletableDeferred<Unit>() }
            val dispatched = List(3) { CompletableDeferred<Unit>() }
            fun start(index: Int) = launch(Dispatchers.IO) {
                val observer = DownloadCallObserver({ queued[index].complete(Unit) }, { dispatched[index].complete(Unit) })
                val request = Request.Builder().url(server.url("/$index")).tag(DownloadCallObserver::class.java, observer).build()
                client.newCall(request).awaitBody { response ->
                    assertEquals(-1L, response.body!!.contentLength())
                    assertEquals("book", response.body!!.string())
                }
            }
            val jobs = mutableListOf<kotlinx.coroutines.Job>()
            try {
                jobs += start(0)
                assertEquals("/0", server.takeRequest(3, TimeUnit.SECONDS)?.path)
                jobs += start(1)
                withTimeout(3_000) { queued[1].await() }
                assertFalse(dispatched[1].isCompleted)
                client.dispatcher.setDownloadConcurrency(3)
                assertEquals("/1", server.takeRequest(3, TimeUnit.SECONDS)?.path)
                assertTrue(dispatched[1].isCompleted)
                client.dispatcher.setDownloadConcurrency(1)
                jobs += start(2)
                withTimeout(3_000) { queued[2].await() }
                jobs[0].cancelAndJoin()
                withTimeout(3_000) { while(client.dispatcher.runningCallsCount() != 1 || client.dispatcher.queuedCallsCount() != 1) delay(5) }
                assertTrue(jobs[1].isActive)
                assertFalse(dispatched[2].isCompleted)
                jobs[1].cancelAndJoin()
                withTimeout(3_000) { jobs[2].join() }
                assertEquals("/2", server.takeRequest(3, TimeUnit.SECONDS)?.path)
                assertTrue(dispatched[2].isCompleted)
            } finally {
                jobs.forEach { it.cancel() }
                jobs.joinAll()
                client.dispatcher.executorService.shutdownNow()
            }
        }
    }

    @Test fun cancellingRequestStopsTheUnderlyingCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val cancelled = CountDownLatch(1)
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun canceled(call: Call) { cancelled.countDown() }
            }).build()
            val api = NoveliaApi(null, server.url("/api/").toString(), client)
            val job = launch(Dispatchers.IO) { api.request("GET", "novel") }
            try {
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            } finally { job.cancelAndJoin() }
            assertTrue("Cancellation must free the socket", cancelled.await(2, TimeUnit.SECONDS))
        }
    }

    @Test fun cancellingDuringBodyReadAlsoStopsTheCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("a".repeat(64 * 1024)).throttleBody(1, 100, TimeUnit.MILLISECONDS))
            val bodyStarted = CountDownLatch(1)
            val cancelled = CountDownLatch(1)
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun responseBodyStart(call: Call) { bodyStarted.countDown() }
                override fun canceled(call: Call) { cancelled.countDown() }
            }).build()
            val job = launch(Dispatchers.IO) { NoveliaApi(null, server.url("/api/").toString(), client).request("GET", "novel") }
            try { assertTrue(bodyStarted.await(5, TimeUnit.SECONDS)) } finally { job.cancelAndJoin() }
            assertTrue(cancelled.await(2, TimeUnit.SECONDS))
        }
    }

    @Test fun onlySuccessfulWritesInvalidateDetails() = runBlocking {
        MockWebServer().use { server ->
            val api = NoveliaApi(null, server.url("/api/").toString())
            server.enqueue(MockResponse().setBody("{}"))
            api.request("GET", "novel")
            assertEquals(0L, api.lastMutationAt)
            server.enqueue(MockResponse().setResponseCode(403))
            runCatching { api.request("PUT", "novel", "{}") }
            assertEquals(0L, api.lastMutationAt)
            server.enqueue(MockResponse().setBody("{}"))
            api.request("PUT", "novel", "{}")
            assertTrue(api.lastMutationAt > 0L)
        }
    }

    @Test fun saturatedDownloadsDoNotBlockReadingAndQueuedDownloadsCanBeCancelled() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.enqueue(MockResponse().setBody("正文"))
            val client = OkHttpClient.Builder().build()
            val api = NoveliaApi(null, server.url("/api/").toString(), client)
            val downloads = api.downloadTransport
            assertSame(downloads, api.downloadTransport)
            assertSame(client.connectionPool, downloads.connectionPool)
            val jobs = (1..2).map { index -> launch(Dispatchers.IO) {
                api.withAuthenticatedResponse(Request.Builder().url(server.url("/file/$index")).build(), client = downloads) {
                    it.body?.string()
                }
            } }
            var queued: kotlinx.coroutines.Job? = null
            try {
                repeat(2) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
                queued = launch(Dispatchers.IO) {
                    api.withAuthenticatedResponse(Request.Builder().url(server.url("/file/queued")).build(), client = downloads) {
                        it.body?.string()
                    }
                }
                withTimeout(5_000) { while(downloads.dispatcher.queuedCallsCount() != 1) delay(10) }
                queued.cancelAndJoin()
                assertEquals("正文", withTimeout(5_000) { api.request("GET", "chapter") })
                assertEquals("/api/chapter", server.takeRequest(5, TimeUnit.SECONDS)?.path)
            } finally {
                queued?.cancelAndJoin()
                jobs.forEach { it.cancel() }
                jobs.joinAll()
            }
            withTimeout(5_000) { while(downloads.dispatcher.runningCallsCount() != 0) delay(10) }
            assertEquals(3, server.requestCount)
        }
    }
}
