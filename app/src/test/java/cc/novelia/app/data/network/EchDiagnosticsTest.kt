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
import okhttp3.Protocol
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class EchDiagnosticsTest {
    @Test fun readsAnonymousResponsesAndReportsParsedForumResultsWithoutBodies() = runBlocking {
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        val engine = responseEngine { request ->
            requests += request
            when (request.url.toString()) {
                EchForumProbe.CATEGORIES_URL -> """[{"id":1,"slug":"announcements"}]"""
                EchForumProbe.POSTS_URL -> """{"total":1,"items":[{"id":1,"title":"private-diagnostic-body"}]}"""
                else -> "private-diagnostic-body"
            }
        }
        val report = EchDiagnostics(engine) { "TLS 1.3 / ECH 握手成功" }.run()
        assertEquals(5, requests.size)
        requests.forEach {
            assertEquals("GET", it.method)
            assertNull(it.header("Authorization"))
            assertNull(it.header("Cookie"))
        }
        assertTrue(report.contains("论坛分类 API\nHTTP 200，读取和解析成功（1 项）"))
        assertTrue(report.contains("论坛帖子 API\nHTTP 200，读取和解析成功（1 项）"))
        assertFalse(report.contains("private-diagnostic-body"))
    }

    @Test fun successfulHttpWithInvalidForumDocumentIsReportedAsParseFailure() = runBlocking {
        val engine = responseEngine { "<html>challenge</html>" }
        val report = EchDiagnostics(engine) { "TLS 1.3 / ECH 握手成功" }.run()
        assertTrue(report.contains("论坛分类 API\nHTTP 200，JSON 解析失败"))
        assertTrue(report.contains("论坛帖子 API\nHTTP 200，JSON 解析失败"))
    }

    @Test fun cancellingDiagnosticsCancelsEveryActiveHttpCallAndSkipsForum() = runBlocking {
        val started = CountDownLatch(3)
        val cancelled = CountDownLatch(3)
        val release = CountDownLatch(1)
        val engine = object : EchEngine {
            override fun open(request: Request, timeouts: EchTimeouts): EchExchange {
                assertEquals("/cdn-cgi/trace", request.url.encodedPath)
                val closed = AtomicBoolean(false)
                return object : EchExchange {
                    override fun execute(): EchReply {
                        started.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        throw IOException("cancelled test request")
                    }
                    override fun read(maxBytes: Long) = ByteArray(0)
                    override fun cancel() {
                        if (closed.compareAndSet(false, true)) cancelled.countDown()
                        release.countDown()
                    }
                }
            }
        }
        val job = launch(Dispatchers.Default) { EchDiagnostics(engine) { "handshake" }.run() }
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS))
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(cancelled.await(3, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            job.cancelAndJoin()
        }
    }

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
