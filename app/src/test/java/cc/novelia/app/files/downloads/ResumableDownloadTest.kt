package cc.novelia.app.files.downloads

import cc.novelia.app.data.network.awaitBody
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResumableDownloadTest {
    @get:Rule val temporary = TemporaryFolder()
    private val etag = "\"version-one\""
    private fun interrupted(validator: Boolean = true) = MockResponse().setBody("abcdefgh")
        .apply { if (validator) setHeader("ETag", etag) }.setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)

    @Test(timeout = 15_000) fun interruptedDownloadAppendsOnlyMatchingRangeAndStrongValidator() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(interrupted())
            server.enqueue(MockResponse().setResponseCode(206).setHeader("ETag", etag)
                .setHeader("Content-Range", "bytes 4-7/8").setBody("efgh"))
            val file = download(server)
            assertEquals("abcdefgh", file.readText())
            assertEquals("identity", server.takeRequest().getHeader("Accept-Encoding"))
            val resumed = server.takeRequest()
            assertEquals("bytes=4-", resumed.getHeader("Range"))
            assertEquals(etag, resumed.getHeader("If-Range"))
        }
    }

    @Test(timeout = 15_000) fun ignoredRangeOrChangedFileReplacesPartialInsteadOfConcatenating() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(interrupted())
            server.enqueue(MockResponse().setHeader("ETag", "\"new\"").setBody("NEW FILE"))
            assertEquals("NEW FILE", download(server).readText())
            assertEquals(2, server.requestCount)
        }
    }

    @Test(timeout = 15_000) fun untrustedPartialResponseForcesAFullRestart() = runBlocking {
        for (response in listOf(
            MockResponse().setResponseCode(206).setHeader("ETag", "\"different\"").setHeader("Content-Range", "bytes 4-7/8").setBody("xxxx"),
            MockResponse().setResponseCode(206).setHeader("ETag", etag).setHeader("Content-Range", "bytes 3-6/8").setBody("xxxx"),
            MockResponse().setResponseCode(206).setHeader("ETag", etag).setHeader("Content-Range", "bytes 4-8/9").setBody("xxxxx"),
            MockResponse().setResponseCode(416))) {
            MockWebServer().use { server ->
                server.enqueue(interrupted()); server.enqueue(response)
                server.enqueue(MockResponse().setBody("clean file"))
                assertEquals("clean file", download(server).readText())
                server.takeRequest(); server.takeRequest()
                assertNull(server.takeRequest().getHeader("Range"))
            }
        }
    }

    @Test(timeout = 15_000) fun absentValidatorRestartsAndHttpErrorsAreNotRetried() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(interrupted(false)); server.enqueue(MockResponse().setBody("fresh"))
            assertEquals("fresh", download(server).readText())
            server.takeRequest()
            assertNull(server.takeRequest().getHeader("Range"))
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            assertNotNull(runCatching { download(server) }.exceptionOrNull())
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun rangeParserRejectsOverflowUnknownSizeAndInvalidIntervals() {
        assertEquals(Triple(4L, 7L, 8L), parseDownloadRange("bytes 4-7/8"))
        listOf("bytes 4-7/*", "bytes 7-4/8", "bytes 0-8/8", "bytes 0-9999999999999999999999/8", "bytes 0-7/999999999")
            .forEach { assertNull(parseDownloadRange(it)) }
    }

    @Test(timeout = 15_000) fun weakOrMalformedValidatorCannotResume() = runBlocking {
        for (validator in listOf("W/$etag", "\"", "\"embedded\"quote\"")) {
            MockWebServer().use { server ->
                server.enqueue(interrupted().setHeader("ETag", validator))
                server.enqueue(MockResponse().setBody("fresh"))
                assertEquals("fresh", download(server).readText())
                server.takeRequest()
                assertNull(server.takeRequest().getHeader("Range"))
            }
        }
    }

    @Test(timeout = 15_000) fun repeatedDisconnectsStopAfterThreeAttempts() = runBlocking {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(interrupted(false)) }
            assertNotNull(runCatching { download(server) }.exceptionOrNull())
            assertEquals(3, server.requestCount)
        }
    }

    @Test(timeout = 15_000) fun storageFailureCannotStartAnotherAttempt() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("file"))
            val invalidFile = File(temporary.newFile(), "cannot-write-here")
            assertNotNull(runCatching { download(server, invalidFile) }.exceptionOrNull())
            assertEquals(1, server.requestCount)
        }
    }

    @Test(timeout = 15_000) fun cancellationCannotStartAnotherAttempt() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(interrupted())
            var checks = 0
            val error = runCatching { download(server) { if (++checks > 3) throw CancellationException("paused") } }.exceptionOrNull()
            assertTrue(error is CancellationException)
            assertEquals(1, server.requestCount)
        }
    }

    private suspend fun download(server: MockWebServer, file: File = temporary.newFile(), check: () -> Unit = {}): File {
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        try {
            resumableDownload(Request.Builder().url(server.url("/file")).build(), file, null, check, { _, _ -> }) { request, consume ->
                client.newCall(request).awaitBody(consume)
            }
            return file
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    }
}
