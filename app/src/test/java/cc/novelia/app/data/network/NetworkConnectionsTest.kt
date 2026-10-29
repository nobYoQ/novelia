package cc.novelia.app.data.network

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class NetworkConnectionsTest {
    private fun ready(id: Long) = NetworkSnapshot(NetworkRoute(id, links = emptyList()), internet = true)

    @Test fun poolRotationPreservesActiveResponseAndMovesDerivedClientsToNewPool() {
        val connections = NetworkConnections(resetNative = {})
        val client = OkHttpClient.Builder().addInterceptor(NetworkGenerationInterceptor(connections)).build()
        val derived = client.forDownloads()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("old response"))
            server.enqueue(MockResponse().setBody("new response"))
            server.enqueue(MockResponse().setBody("reused new pool"))
            val request = Request.Builder().url(server.url("/")).build()
            client.newNetworkCall(request).execute().use { old ->
                connections.update(ready(1))
                derived.newNetworkCall(request).execute().use { assertEquals("new response", it.body!!.string()) }
                assertEquals("old response", old.body!!.string())
            }
            client.newNetworkCall(request).execute().use { assertEquals("reused new pool", it.body!!.string()) }
            assertEquals(0, server.takeRequest().sequenceNumber)
            assertEquals(0, server.takeRequest().sequenceNumber)
            assertEquals(1, server.takeRequest().sequenceNumber)
        }
        connections.reset()
        client.dispatcher.executorService.shutdownNow()
        derived.dispatcher.executorService.shutdownNow()
    }

    @Test(timeout = 10_000) fun stalledReadIsCancelledAndRebuiltOnceOnNewGeneration() = runBlocking {
        var time = 0L
        val connections = NetworkConnections(resetNative = {}, now = { time })
        connections.update(ready(1))
        val first = BlockingExchange()
        val opened = AtomicInteger()
        val client = client(connections, object : EchEngine {
            override fun open(request: Request, timeouts: EchTimeouts): EchExchange =
                if (opened.incrementAndGet() == 1) first else ImmediateExchange("new chapter")
        })
        val read = async(Dispatchers.IO) { NoveliaApi(null, transport = client).request("GET", "chapter") }
        try {
            assertTrue(first.started.await(2, TimeUnit.SECONDS))
            connections.update(ready(2))
            time = 2_999; connections.cancelStalledReads()
            assertFalse(first.cancelled)
            time = 3_001; connections.cancelStalledReads()
            assertEquals("new chapter", withTimeout(3_000) { read.await() })
            assertTrue(first.cancelled)
            assertEquals(2, opened.get())
            // A later scan cannot cancel or publish the completed old attempt again.
            time = 20_000; connections.cancelStalledReads()
            assertEquals(2, opened.get())
        } finally {
            first.cancel(); read.cancelAndJoin(); client.dispatcher.executorService.shutdownNow()
        }
    }

    @Test(timeout = 10_000) fun writesAndStreamingReadsAreNotCancelledOrReplayed() = runBlocking {
        for (method in listOf("POST", "GET")) {
            var time = 0L
            val connections = NetworkConnections(resetNative = {}, now = { time })
            connections.update(ready(1))
            val exchange = BlockingExchange()
            val opened = AtomicInteger()
            val client = client(connections, object : EchEngine {
                override fun open(request: Request, timeouts: EchTimeouts): EchExchange { opened.incrementAndGet(); return exchange }
            })
            val api = NoveliaApi(null, transport = client)
            val read = async(Dispatchers.IO) {
                if (method == "POST") api.request(method, "comment", "synthetic")
                else api.withAuthenticatedResponse(Request.Builder().url("https://n.novelia.cc/file").build()) { it.body!!.string() }
            }
            try {
                assertTrue(exchange.started.await(2, TimeUnit.SECONDS))
                connections.update(ready(2)); time = 10_000; connections.cancelStalledReads()
                assertFalse(exchange.cancelled)
                exchange.release.countDown()
                assertEquals("old chapter", withTimeout(3_000) { read.await() })
                assertEquals(1, opened.get())
            } finally {
                exchange.cancel(); read.cancelAndJoin(); client.dispatcher.executorService.shutdownNow()
            }
        }
    }

    @Test(timeout = 10_000) fun repeatedSwitchesNeverCreateAnUnboundedRetryLoop() = runBlocking {
        val connections = NetworkConnections(resetNative = {})
        val opened = AtomicInteger()
        val client = client(connections, object : EchEngine {
            override fun open(request: Request, timeouts: EchTimeouts): EchExchange {
                connections.update(ready(opened.incrementAndGet().toLong()))
                return object : EchExchange {
                    override fun execute(): EchReply = throw IOException("synthetic network loss")
                    override fun read(maxBytes: Long) = ByteArray(0)
                    override fun cancel() = Unit
                }
            }
        })
        try {
            val result = runCatching { NoveliaApi(null, transport = client).request("GET", "chapter") }
            assertTrue(result.exceptionOrNull() is NetworkChangedException)
            assertEquals(2, opened.get())
        } finally { client.dispatcher.executorService.shutdownNow() }
    }

    @Test(timeout = 10_000) fun cancellationDuringSwitchCannotStartReplacement() = runBlocking {
        val connections = NetworkConnections(resetNative = {})
        val exchange = BlockingExchange()
        val opened = AtomicInteger()
        val client = client(connections, object : EchEngine {
            override fun open(request: Request, timeouts: EchTimeouts): EchExchange { opened.incrementAndGet(); return exchange }
        })
        val read = async(Dispatchers.IO) { NoveliaApi(null, transport = client).request("GET", "chapter") }
        try {
            assertTrue(exchange.started.await(2, TimeUnit.SECONDS))
            connections.update(ready(2))
            read.cancelAndJoin()
            assertEquals(1, opened.get())
        } finally { exchange.cancel(); client.dispatcher.executorService.shutdownNow() }
    }

    @Test fun progressAfterSwitchExtendsGraceAndFinishedAttemptCannotCancelAnotherCall() {
        var time = 0L
        val connections = NetworkConnections(resetNative = {}, now = { time })
        val client = OkHttpClient()
        val request = Request.Builder().url("https://example.invalid/").build()
        val call = client.newCall(request)
        val attempt = connections.track(call, connections.generation())
        connections.update(ready(1))
        time = 2_900; connections.progress(attempt)
        time = 4_000; connections.cancelStalledReads()
        assertFalse(call.isCanceled())
        connections.finished(attempt)
        val replacement = client.newCall(request)
        connections.track(replacement, connections.generation())
        time = 10_000; connections.cancelStalledReads()
        assertFalse(call.isCanceled())
        assertFalse(replacement.isCanceled())
    }

    @Test(timeout = 10_000) fun certificateFailureAndHttpErrorsAreNotReplayedAfterSwitch() = runBlocking {
        for (certificate in listOf(true, false)) {
            val connections = NetworkConnections(resetNative = {})
            val opened = AtomicInteger()
            val client = client(connections, object : EchEngine {
                override fun open(request: Request, timeouts: EchTimeouts): EchExchange {
                    connections.update(ready(opened.incrementAndGet().toLong()))
                    return object : ImmediateExchange("denied") {
                        override fun execute(): EchReply {
                            if (certificate) throw EchIOException("证书验证未通过", false)
                            return EchReply(403, Protocol.HTTP_2, Headers.Builder().build(), -1)
                        }
                    }
                }
            })
            try {
                val error = runCatching { NoveliaApi(null, transport = client).request("GET", "chapter") }.exceptionOrNull()
                assertFalse(error is NetworkChangedException)
                assertTrue(error is IOException)
                assertEquals(1, opened.get())
            } finally { client.dispatcher.executorService.shutdownNow() }
        }
    }

    private fun client(connections: NetworkConnections, engine: EchEngine) = OkHttpClient.Builder()
        .addInterceptor(NetworkGenerationInterceptor(connections))
        .addInterceptor(EchInterceptor(engine, { true }))
        .addInterceptor { error("Synthetic requests must not reach the network") }.build()

    private open class ImmediateExchange(content: String) : EchExchange {
        private val data = Buffer().writeUtf8(content)
        override fun execute() = EchReply(200, Protocol.HTTP_2, Headers.Builder().build(), -1)
        override fun read(maxBytes: Long) = data.readByteArray(minOf(maxBytes, data.size))
        override fun cancel() = Unit
    }

    private class BlockingExchange : ImmediateExchange("old chapter") {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile var cancelled = false
        override fun read(maxBytes: Long): ByteArray {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            if (cancelled) throw IOException("synthetic cancellation")
            return super.read(maxBytes)
        }
        override fun cancel() { cancelled = true; release.countDown() }
    }
}
