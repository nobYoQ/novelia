package cc.novelia.app.data.network

import cc.novelia.app.data.auth.AuthenticationSession
import cc.novelia.app.data.auth.SessionBinding
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

/** Synthetic requests exercise the application contract without accounts or network access. */
class EchApiContractTest {
    @Test(timeout = 10_000) fun unauthorizedResponseRefreshesThroughEchAndRetriesWithNewToken() = runBlocking {
        val requests = ConcurrentLinkedQueue<Request>()
        val unauthorized = ImmediateExchange("expired", code = 401)
        val refreshes = AtomicInteger()
        val engine = object : EchEngine {
            override fun open(request: Request, timeouts: EchTimeouts): EchExchange {
                requests.add(request)
                return when (request.url.host) {
                    "auth.novelia.cc" -> {
                        assertTrue("401 response must close before refreshing", unauthorized.cancelled.get())
                        assertEquals("POST", request.method)
                        assertEquals("synthetic-session=old", request.header("Cookie"))
                        assertNull(request.header("Authorization"))
                        ImmediateExchange("synthetic-new-token", headers = Headers.Builder()
                            .add("Set-Cookie", "synthetic-session=new")
                            .add("Set-Cookie", "synthetic-refresh=new")
                            .build())
                    }
                    "n.novelia.cc" -> when (request.header("Authorization")) {
                        "Bearer synthetic-old-token" -> unauthorized
                        "Bearer synthetic-new-token" -> ImmediateExchange("chapter content")
                        else -> error("Unexpected synthetic token")
                    }
                    else -> error("Unexpected destination")
                }
            }
        }
        val client = client(engine)
        val session = object : AuthenticationSession {
            private val binding = SessionBinding("synthetic-account", 1)
            private var token = "synthetic-old-token"
            override fun capture() = binding
            override fun tokenFor(binding: SessionBinding): String {
                check(binding == this.binding)
                return token
            }
            override suspend fun refreshIfCurrent(binding: SessionBinding, previousToken: String?): Boolean {
                assertEquals(tokenFor(binding), previousToken)
                refreshes.incrementAndGet()
                val request = Request.Builder().url("https://auth.novelia.cc/api/v1/auth/refresh?app=n")
                    .header("Cookie", "synthetic-session=old")
                    .post(ByteArray(0).toRequestBody()).build()
                token = client.newCall(request).awaitBody { response ->
                    assertEquals(200, response.code)
                    assertEquals(listOf("synthetic-session=new", "synthetic-refresh=new"), response.headers.values("Set-Cookie"))
                    response.body!!.string()
                }
                return true
            }
        }
        try {
            assertEquals("chapter content", NoveliaApi(session, transport = client).request("GET", "chapter"))
            assertEquals(1, refreshes.get())
            assertEquals(listOf("n.novelia.cc", "auth.novelia.cc", "n.novelia.cc"), requests.map { it.url.host })
            assertEquals(listOf("GET", "POST", "GET"), requests.map { it.method })
            assertEquals(listOf("Bearer synthetic-old-token", null, "Bearer synthetic-new-token"), requests.map { it.header("Authorization") })
        } finally {
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }

    @Test(timeout = 15_000) fun cancellingOneStreamingDownloadLeavesOtherDownloadAndApiUsable() = runBlocking {
        val first = BlockingExchange("first file")
        val second = BlockingExchange("second file")
        val engine = object : EchEngine {
            override fun open(request: Request, timeouts: EchTimeouts): EchExchange = when (request.url.encodedPath) {
                "/first" -> first
                "/second" -> second
                "/api/chapter" -> ImmediateExchange("chapter content")
                else -> error("Unexpected synthetic request")
            }
        }
        val client = client(engine)
        val downloads = client.forDownloads()
        suspend fun download(path: String): String = downloads.newCall(Request.Builder()
            .url("https://n.novelia.cc/$path").build()).awaitBody { it.body!!.string() }
        val firstDownload = async(Dispatchers.IO) { download("first") }
        val secondDownload = async(Dispatchers.IO) { download("second") }
        try {
            assertTrue("first download did not enter body read", first.readStarted.await(3, TimeUnit.SECONDS))
            assertTrue("second download did not enter body read", second.readStarted.await(3, TimeUnit.SECONDS))
            val api = NoveliaApi(null, transport = client)
            assertEquals("chapter content", withTimeout(2_000) { api.request("GET", "chapter") })

            firstDownload.cancelAndJoin()
            assertTrue("cancel must reach the blocked exchange", first.closed.await(3, TimeUnit.SECONDS))
            assertTrue("cancel must release its body reader", first.readFinished.await(3, TimeUnit.SECONDS))
            assertFalse("cancelling one download affected the other", second.cancelled.get())
            assertEquals("chapter content", withTimeout(2_000) { api.request("GET", "chapter") })

            second.release.countDown()
            assertEquals("second file", withTimeout(3_000) { secondDownload.await() })
            assertTrue("completed response must release its exchange", second.closed.await(1, TimeUnit.SECONDS))
        } finally {
            first.release.countDown()
            second.release.countDown()
            firstDownload.cancelAndJoin()
            secondDownload.cancelAndJoin()
            downloads.dispatcher.cancelAll()
            client.dispatcher.cancelAll()
            downloads.dispatcher.executorService.shutdownNow()
            client.dispatcher.executorService.shutdownNow()
        }
    }

    private fun client(engine: EchEngine) = OkHttpClient.Builder()
        .addInterceptor(EchInterceptor(engine, { true }))
        .addInterceptor { error("Synthetic ECH tests must never reach the network") }
        .build()

    private class ImmediateExchange(
        content: String,
        private val code: Int = 200,
        private val headers: Headers = Headers.Builder().build()
    ) : EchExchange {
        private val data = Buffer().writeUtf8(content)
        val cancelled = AtomicBoolean()
        override fun execute() = EchReply(code, Protocol.HTTP_2, headers, -1)
        override fun read(maxBytes: Long): ByteArray = data.readByteArray(minOf(maxBytes, data.size))
        override fun cancel() { cancelled.set(true) }
    }

    private class BlockingExchange(content: String) : EchExchange {
        private val data = Buffer().writeUtf8(content)
        val readStarted = CountDownLatch(1)
        val readFinished = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val cancelled = AtomicBoolean()
        override fun execute() = EchReply(200, Protocol.HTTP_2, Headers.Builder().build(), -1)
        override fun read(maxBytes: Long): ByteArray {
            readStarted.countDown()
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw IOException("Synthetic body wait expired")
                if (cancelled.get()) throw IOException("Synthetic body cancelled")
                return data.readByteArray(minOf(maxBytes, data.size))
            } finally { readFinished.countDown() }
        }
        override fun cancel() {
            cancelled.set(true)
            closed.countDown()
            release.countDown()
        }
    }
}
