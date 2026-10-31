package cc.novelia.app.data.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class RequestRecoveryApiTest {
    private fun client(): OkHttpClient {
        val connections = NetworkConnections(resetNative = {})
        connections.update(NetworkSnapshot(NetworkRoute(1, links = emptyList()), internet = true))
        return OkHttpClient.Builder().addInterceptor(NetworkGenerationInterceptor(connections)).build()
    }

    @Test(timeout = 10_000) fun truncatedReadIsReplacedWithoutReturningPartialText() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("partial old body").setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            server.enqueue(MockResponse().setBody("complete replacement"))
            val client = client()
            try {
                assertEquals("complete replacement", NoveliaApi(null, server.url("/").toString(), client).request("GET", "chapter"))
                assertEquals(2, server.requestCount)
            } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
        }
    }

    @Test(timeout = 10_000) fun lostMutationResponseIsNeverReplayed() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val client = client()
            try {
                val error = runCatching { NoveliaApi(null, server.url("/").toString(), client).request("POST", "comment", "synthetic body") }.exceptionOrNull()
                assertTrue(error is IOException)
                assertEquals(1, server.requestCount)
            } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
        }
    }

    @Test(timeout = 10_000) fun overallBudgetCancelsSlowBodyWithoutStartingAnotherRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("long response").throttleBody(1, 250, TimeUnit.MILLISECONDS))
            val client = client().newBuilder().callTimeout(300, TimeUnit.MILLISECONDS).build()
            try {
                val error = withTimeout(3_000) {
                    runCatching { NoveliaApi(null, server.url("/").toString(), client).request("GET", "chapter") }.exceptionOrNull()
                }
                assertTrue(error is IOException)
                assertEquals(1, server.requestCount)
            } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
        }
    }

    @Test(timeout = 10_000) fun streamingDownloadCanOutliveForegroundBudget() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("complete file").setBodyDelay(200, TimeUnit.MILLISECONDS))
            val client = client().newBuilder().callTimeout(50, TimeUnit.MILLISECONDS).build().forDownloads()
            try {
                val body = withTimeout(3_000) { client.newNetworkCall(Request.Builder().url(server.url("/file")).build()).awaitBody { it.body!!.string() } }
                assertEquals("complete file", body)
                assertEquals(1, server.requestCount)
            } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
        }
    }
}
