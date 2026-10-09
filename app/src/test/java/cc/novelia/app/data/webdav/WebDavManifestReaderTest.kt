package cc.novelia.app.data.webdav

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class WebDavManifestReaderTest {
    private fun manifest(id: String) = """{"format":"novelia-webdav","schemaVersion":1,"datasetId":"$id"}"""

    @Test fun cachedManifestIsRevalidatedAndDisappearanceOrDatasetChangesStillStopSync() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(manifest("dataset-a")).setHeader("ETag", "\"v1\""))
            server.enqueue(MockResponse().setResponseCode(304))
            server.enqueue(MockResponse().setBody(manifest("dataset-b")).setHeader("ETag", "\"v2\""))
            server.enqueue(MockResponse().setResponseCode(404))
            server.start()
            val binding = WebDavConfig(endpoint = server.url("/dav/").toString(), allowInsecureHttp = true, datasetId = "dataset-a")
            val client = WebDavClient(binding, "")
            val reader = WebDavManifestReader(Json)
            assertEquals("dataset-a", reader.read(client, binding)!!.datasetId)
            assertEquals("dataset-a", reader.read(client, binding)!!.datasetId)
            assertNull(server.takeRequest().getHeader("If-None-Match"))
            assertEquals("\"v1\"", server.takeRequest().getHeader("If-None-Match"))
            try { reader.read(client, binding); fail("Expected changed dataset rejection") }
            catch(_: IllegalArgumentException) { }
            try { reader.read(client, binding); fail("Expected missing manifest rejection") }
            catch(_: IllegalArgumentException) { }
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun configurationChangesCannotReuseAnotherDirectorysManifest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(manifest("dataset-a")).setHeader("ETag", "\"same-tag\""))
            server.enqueue(MockResponse().setBody(manifest("dataset-b")).setHeader("ETag", "\"same-tag\""))
            server.start()
            val binding = WebDavConfig(endpoint = server.url("/dav/").toString(), allowInsecureHttp = true)
            val other = binding.copy(folder = "other", generation = 1)
            val reader = WebDavManifestReader(Json)
            assertEquals("dataset-a", reader.read(WebDavClient(binding, ""), binding)!!.datasetId)
            assertEquals("dataset-b", reader.read(WebDavClient(other, ""), other)!!.datasetId)
            repeat(2) { assertNull(server.takeRequest().getHeader("If-None-Match")) }
        }
    }
}
