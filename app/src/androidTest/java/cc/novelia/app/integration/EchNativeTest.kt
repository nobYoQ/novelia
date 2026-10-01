package cc.novelia.app.integration

import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.network.EchForumProbe
import cc.novelia.app.data.network.NoveliaApi
import cc.novelia.app.data.model.WebDetail
import cc.novelia.app.data.model.DownloadEntry
import cc.novelia.app.files.DownloadWorker
import cc.novelia.nativeech.ech.Ech
import cc.novelia.nativeech.ech.Upload
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class EchNativeTest {
    @Test fun downloadWorkerSavesPublicTxtAndEpubWithUnicodeResponseFilenames() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("echLive") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        assertNull("Live downloads require a dedicated anonymous test device", app.session.profile.value)
        val wasEnabled = app.ech.enabled.value
        app.ech.setEnabled(true)
        try {
            val api = NoveliaApi(null, transport = app.api.transport)
            val ref = api.webList(0).items.filter { it.total > 0 }.minBy { it.total }.card().ref
            for (format in listOf("txt", "epub")) {
                val id = UUID.randomUUID().toString()
                val entry = DownloadEntry(id, "ECH 下载回归", "ech-test-$id.$format",
                    api.downloadUrl(ref, null, "jp", listOf("sakura", "gpt", "youdao"), false, format, "ECH下载测试.$format"))
                try {
                    DownloadWorker.enqueue(app, entry)
                    val state = withTimeout(90_000) {
                        app.store.state.first { state -> state.downloads.any {
                            it.id == id && it.status in setOf("已完成", "失败", "需要登录")
                        } }
                    }
                    val downloaded = state.downloads.single { it.id == id }
                    assertEquals(downloaded.error ?: "Download did not complete", "已完成", downloaded.status)
                    assertEquals(100, downloaded.progress)
                    val file = File(app.store.downloadsDir, downloaded.fileName)
                    assertTrue(file.length() in 1..(8L * 1024 * 1024))
                    if (format == "epub") file.inputStream().use { input ->
                        assertEquals('P'.code, input.read())
                        assertEquals('K'.code, input.read())
                    }
                    else assertTrue(file.readText().isNotBlank())
                } finally { DownloadWorker.remove(app, id) }
            }
        } finally { app.ech.setEnabled(wasEnabled) }
    }

    @Test fun applicationTransportReadsCompleteResponsesAndParsesDiscovery() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("echLive") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        val wasEnabled = app.ech.enabled.value
        app.ech.setEnabled(true)
        try {
            for (host in listOf("n.novelia.cc", "auth.novelia.cc", "forum.novelia.cc")) {
                val request = Request.Builder().url("https://$host/cdn-cgi/trace").get().build()
                app.api.transport.newCall(request).execute().use { response ->
                    assertEquals(host, 200, response.code)
                    val bytes = response.body!!.bytes() // Includes JNI EOF and OkHttp content-length checks.
                    assertTrue(bytes.isNotEmpty())
                    assertTrue(bytes.size < 65536)
                }
            }
            // Explicitly anonymous, through the same API decoding and transport used by discovery.
            val api = NoveliaApi(null, transport = app.api.transport)
            val page = api.webList(0)
            assertTrue(page.pageNumber > 0) // API returns the number of pages, not the requested index.
            assertTrue(page.items.isNotEmpty())
            // Future forum support stays verifiable without its preview branch's business classes.
            for ((url, categories) in listOf(EchForumProbe.CATEGORIES_URL to true, EchForumProbe.POSTS_URL to false)) {
                val request = Request.Builder().url(url).header("Accept", "application/json").build()
                app.api.transport.newCall(request).execute().use { response ->
                    assertEquals(200, response.code)
                    val json = response.body!!.string()
                    assertTrue((if (categories) EchForumProbe.categoryCount(json) else EchForumProbe.postCount(json)) > 0)
                }
            }
            val ref = page.items.first { it.total > 0 }.card().ref
            val detail = api.get<WebDetail>("novel/${ref.key}")
            val chapterId = detail.toc.firstNotNullOf { it.chapterId }
            assertTrue(api.chapter(ref, chapterId).paragraphs.isNotEmpty())
        } finally { app.ech.setEnabled(wasEnabled) }
    }

    @Test fun diagnosisExercisesAnonymousForumApi() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("echLive") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        val report = app.ech.diagnose()
        assertTrue(report, report.contains("论坛分类 API\nHTTP 200，读取和解析成功"))
        assertTrue(report, report.contains("论坛帖子 API\nHTTP 200，读取和解析成功"))
    }

    @Test fun resettingNetworkStatePreservesActiveBodyAndAllowsFreshConnections() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("echLive") == "true")
        go.Seq.setContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val client = Ech.newClient()
        val first = client.newCall("GET", "https://forum.novelia.cc/cdn-cgi/trace", "{}", null, 0, 8000, 20000, 20000)
        try {
            assertEquals(200L, first.execute().statusCode())
            assertEquals(1, first.read(1)!!.size)
            client.resetNetworkState() // Same native operation invoked by the Android network callback.
            var length = 1
            while (true) {
                val bytes = first.read(4096) ?: ByteArray(0)
                if (bytes.isEmpty()) break
                length += bytes.size
                assertTrue(length < 65536)
            }
            assertTrue(length > 1)
        } finally { first.cancel() }
        val next = client.newCall("GET", "https://forum.novelia.cc/cdn-cgi/trace", "{}", null, 0, 8000, 20000, 20000)
        try {
            assertEquals(200L, next.execute().statusCode())
            assertTrue(next.read(4096)!!.isNotEmpty())
        } finally { next.cancel() }
    }

    @Test fun nativeLibraryLoadsAndCancellationClosesUploadWithoutNetwork() {
        go.Seq.setContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val client = Ech.newClient()
        val closed = AtomicBoolean(false)
        val upload = object : Upload {
            override fun readChunk(maxBytes: Long): ByteArray = error("cancelled call must not read upload")
            override fun close() { closed.set(true) }
        }
        val call = client.newCall("POST", "https://auth.novelia.cc/", "{}", upload, 0, 1000, 1000, 1000)
        call.cancel()
        assertTrue(closed.get())
        try { call.execute(); fail("cancelled call executed") } catch (_: Exception) { /* expected */ }
    }

    @Test fun publicReadOnlyECHRequests() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("echLive") == "true")
        go.Seq.setContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val client = Ech.newClient()
        for (host in listOf("n.novelia.cc", "auth.novelia.cc", "forum.novelia.cc")) {
            val call = client.newCall("GET", "https://$host/cdn-cgi/trace", "{}", null, 0, 8000, 20000, 20000)
            try {
                val response = call.execute() // Native bridge rejects a connection without accepted ECH.
                assertEquals(200L, response.statusCode())
                var length = 0
                while (true) {
                    val bytes = call.read(4096) ?: ByteArray(0)
                    if (bytes.isEmpty()) break
                    length += bytes.size
                    assertTrue(length < 65536)
                }
                assertTrue(length > 0)
            } finally { call.cancel() }
        }
    }
}
