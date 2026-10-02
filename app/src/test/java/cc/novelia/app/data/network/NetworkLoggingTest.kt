package cc.novelia.app.data.network

import cc.novelia.app.data.storage.appJson
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NetworkLoggingTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun rotatesWithinBoundAndExportsReadableUtf8Archive() {
        val directory = temporary.newFolder()
        val store = NetworkLogStore(directory, 1024)
        repeat(100) { store.append(NetworkLogEvent(stage = "tcp", outcome = "failed", reason = "timeout")) }
        assertTrue(directory.listFiles()!!.sumOf { it.length() } <= 2048)
        store.saveReport("中文诊断结果")
        val files = unzip(networkLogArchive(store.snapshot(), "{}", 3))
        assertEquals("中文诊断结果", files["diagnosis.txt"])
        assertTrue(files.getValue("README.txt").contains("事件数：3"))
        files.filterKeys { it.endsWith("jsonl") }.values.forEach { text ->
            text.lineSequence().filter { it.isNotEmpty() }.forEach { appJson.decodeFromString<NetworkLogEvent>(it) }
        }
        store.clear(); assertTrue(store.snapshot().isEmpty())
    }

    @Test fun directRequestsLogPhasesAndCompleteBodyWithoutCredentialsOrUrls() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("private-body").setHeader("Set-Cookie", "private-cookie"))
        val recorder = NetworkRecorder(NetworkLogStore(temporary.newFolder())) { true }
        val client = recorder.attach(OkHttpClient.Builder()) { false }.build()
        try {
            val request = Request.Builder().url(server.url("/private-name?q=private-search"))
                .header("Authorization", "private-token").header("Cookie", "private-request-cookie").build()
            assertEquals("private-body", client.newCall(request).execute().use { it.body!!.string() })
            val archive = unzip(recorder.export("{}"))
            val events = archive.filterKeys { it.endsWith("jsonl") }.values.joinToString("")
            assertFalse(events.contains("private")); assertFalse(events.contains(server.hostName)); assertFalse(events.contains("Set-Cookie"))
            val rows = events.lineSequence().filter { it.isNotEmpty() }.map { appJson.decodeFromString<NetworkLogEvent>(it) }.toList()
            assertTrue(rows.any { it.stage == "tcp" })
            assertTrue(rows.any { it.stage == "response_headers" && it.status == 200 })
            assertEquals(1, rows.count { it.stage == "call" && it.outcome == "complete" && it.bytes == 12L })
        } finally { server.close() }
    }

    @Test fun echBodyFailureIsNotReportedAsSuccessAndDisabledRecordingRetainsNoBusinessData() = runBlocking {
        var enabled = true
        val recorder = NetworkRecorder(NetworkLogStore(temporary.newFolder())) { enabled }
        val engine = object : EchEngine {
            override fun open(request: Request, timeouts: EchTimeouts): EchExchange = object : EchExchange {
                private var sent = false
                override fun execute() = EchReply(200, Protocol.HTTP_2, Headers.Builder().build(), -1)
                override fun read(maxBytes: Long): ByteArray {
                    if (sent) throw IOException("private-error-with-token")
                    sent = true; return "123".toByteArray()
                }
                override fun cancel() {}
            }
        }
        val client = recorder.attach(OkHttpClient.Builder()) { true }.addInterceptor(EchInterceptor(engine, { true })).build()
        fun request() {
            try { client.newCall(Request.Builder().url("https://n.novelia.cc/api/novel/private-id/file?filename=private-title").build()).execute().use { it.body!!.bytes() }; fail() }
            catch (_: IOException) { }
        }
        request()
        val logs = unzip(recorder.export("{}")).filterKeys { it.endsWith("jsonl") }.values.joinToString("")
        assertFalse(logs.contains("private")); assertFalse(logs.contains("\"outcome\":\"complete\""))
        val rows = logs.lineSequence().filter { it.isNotEmpty() }.map { appJson.decodeFromString<NetworkLogEvent>(it) }.toList()
        assertTrue(rows.any { it.stage == "call" && it.outcome == "failed" && it.bytes == 3L })
        enabled = false; recorder.clear(); request()
        assertFalse(unzip(recorder.export("{}")).keys.any { it.endsWith("jsonl") })
    }

    @Test fun diagnosticsAreRecordedEvenWhenBusinessRecordingIsOff() = runBlocking {
        val recorder = NetworkRecorder(NetworkLogStore(temporary.newFolder())) { false }
        val client = recorder.attach(OkHttpClient.Builder()) { false }.addInterceptor { throw IOException("private-failure") }.build()
        val request = Request.Builder().url("https://n.novelia.cc/cdn-cgi/trace")
            .tag(DiagnosticRequestTag::class.java, DiagnosticRequestTag("test-run", "direct")).build()
        try { client.newCall(request).execute(); fail() } catch (_: IOException) { }
        val logs = unzip(recorder.export("{}")).filterKeys { it.endsWith("jsonl") }.values.joinToString("")
        assertTrue(logs.contains("diagnostic")); assertTrue(logs.contains("test-run")); assertFalse(logs.contains("private-failure"))
    }

    @Test fun nativeEventsRemainIndependentAcrossRedirectExchangesAndDeduplicateSnapshots() {
        val rows = mutableListOf<NetworkLogEvent>()
        val trace = NetworkRequestTrace(Request.Builder().url("https://n.novelia.cc/api/novel/id/file").build(), "ECH", rows::add)
        val first = trace.nextNativeExchange()
        val second = trace.nextNativeExchange()
        val events = """[{"stage":"tls","outcome":"ok","atMillis":20},{"stage":"ech","outcome":"accepted","atMillis":21}]"""
        trace.native(first, events); trace.native(first, events); trace.native(second, events)
        assertEquals(4, rows.size)
        assertEquals(listOf(first, first, second, second), rows.map { it.exchange })
    }

    private fun unzip(bytes: ByteArray): Map<String, String> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; put(entry.name, zip.readBytes().toString(Charsets.UTF_8)) }
        }
    }
}
