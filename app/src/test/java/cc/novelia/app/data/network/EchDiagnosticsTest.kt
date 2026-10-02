package cc.novelia.app.data.network

import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class EchDiagnosticsTest {
    @Test fun comparesSeparateAnonymousRoutesAndPublishesIncrementalResultsWithoutBodies() = runBlocking {
        val echRequests = Collections.synchronizedList(mutableListOf<Request>())
        val directRequests = Collections.synchronizedList(mutableListOf<Request>())
        val engine = responseEngine { request -> echRequests += request; document(request) }
        val direct = OkHttpClient.Builder().addInterceptor { chain ->
            directRequests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_2).code(200).message("")
                .body(document(chain.request()).toResponseBody()).build()
        }.build()
        val completed = mutableListOf<Int>()
        val report = EchDiagnostics(echClient(engine), direct).run { _, count, total -> assertEquals(14, total); completed += count }
        assertEquals((0..14).toList(), completed)
        assertEquals(7, echRequests.size); assertEquals(7, directRequests.size)
        (echRequests + directRequests).forEach {
            assertEquals("GET", it.method)
            assertNull(it.header("Authorization")); assertNull(it.header("Cookie"))
        }
        assertTrue(report.contains("论坛分类 API · ECH\nHTTP 200"))
        assertTrue(report.contains("论坛帖子 API · 直连\nHTTP 200"))
        assertTrue(report.contains("文库列表"))
        assertFalse(report.contains("private-diagnostic-body"))
    }

    @Test fun distinguishesHttpErrorsAndInvalidDocumentsFromConnectionFailures() = runBlocking {
        val client = echClient(responseEngine { "<html>private-diagnostic-body</html>" })
        val forbidden = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_2).code(403).message("")
                .body("private-denied-body".toResponseBody()).build()
        }.build()
        val report = EchDiagnostics(client, forbidden).run()
        assertTrue(report.contains("HTTP 200，正文已读完，JSON 格式不符合预期"))
        assertTrue(report.contains("HTTP 403，连接及正文读取成功"))
        assertFalse(report.contains("private"))
    }

    @Test fun cancellingDiagnosticsCancelsBothActiveRoutesAndDoesNotStartRemainingChecks() = runBlocking {
        val started = CountDownLatch(2)
        val cancelled = CountDownLatch(2)
        val release = CountDownLatch(1)
        val engine = object : EchEngine {
            override fun open(request: Request, timeouts: EchTimeouts): EchExchange {
                assertEquals("/cdn-cgi/trace", request.url.encodedPath)
                val closed = AtomicBoolean(false)
                return object : EchExchange {
                    override fun execute(): EchReply {
                        started.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        throw IOException("private-cancelled-request")
                    }
                    override fun read(maxBytes: Long) = ByteArray(0)
                    override fun cancel() { if (closed.compareAndSet(false, true)) cancelled.countDown(); release.countDown() }
                }
            }
        }
        val job = launch(Dispatchers.Default) { EchDiagnostics(echClient(engine), echClient(engine)).run() }
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS))
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(cancelled.await(3, TimeUnit.SECONDS))
        } finally { release.countDown(); job.cancelAndJoin() }
    }

    private fun document(request: Request) = when (request.url.toString()) {
        EchForumProbe.CATEGORIES_URL -> """[{"id":1,"slug":"announcements"}]"""
        EchForumProbe.POSTS_URL -> """{"total":1,"items":[{"id":1,"title":"private-diagnostic-body"}]}"""
        else -> """{"value":"private-diagnostic-body"}"""
    }
    private fun echClient(engine: EchEngine) = OkHttpClient.Builder().addInterceptor(EchInterceptor(engine, { true })).build()
    private fun responseEngine(body: (Request) -> String) = object : EchEngine {
        override fun open(request: Request, timeouts: EchTimeouts): EchExchange {
            val data = Buffer().writeUtf8(body(request))
            return object : EchExchange {
                override fun execute() = EchReply(200, Protocol.HTTP_2, Headers.Builder().build(), -1)
                override fun read(maxBytes: Long) = data.readByteArray(minOf(maxBytes, data.size))
                override fun cancel() {}
            }
        }
    }
}
