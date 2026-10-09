package cc.novelia.app.data.webdav

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class WebDavRecoveryTest {
    private fun binding(server: MockWebServer) = WebDavConfig(endpoint = server.url("/dav/").toString(),
        username = "reader", folder = "reading", enabled = true, generation = 8, deviceId = "device-a",
        deviceName = "Reader", datasetId = "old-dataset", allowInsecureHttp = true)

    private fun manifest(id: String) = """{"format":"novelia-webdav","schemaVersion":1,"datasetId":"$id"}"""

    @Test fun missingDirectoryOffersRecoveryBeforeAnyWriteAndCanBeRecreatedAfterConfirmation() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(201))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"new-version\""))
            server.enqueue(MockResponse().setBody(manifest("new-dataset")).setHeader("ETag", "\"new-version\""))
            server.start()
            val old = binding(server)
            val reader = WebDavManifestReader(Json)
            val recovery = try {
                reader.read(WebDavClient(old, ""), old)
                error("Expected recovery request")
            } catch(error: WebDavRecoveryRequiredException) { error.recovery }
            assertEquals(WebDavRecoveryReason.MISSING_DATASET, recovery.reason)
            assertTrue(recovery.matches(old))
            assertEquals(1, server.requestCount)
            assertEquals("GET", server.takeRequest().method)

            // 对应用户确认：只解除旧绑定，保留连接配置和本地选择，再走首次预览与创建。
            val reconnecting = old.withoutDatasetBinding(recovery)
            assertEquals(old.copy(datasetId = null, generation = old.generation + 1), reconnecting)
            assertFalse(reconnecting.automaticallySyncable())
            val client = WebDavClient(reconnecting, "")
            client.ensureDirectory()
            assertNull(reader.read(client, reconnecting))
            client.put("manifest.json", manifest("new-dataset").toByteArray(), createOnly = true)
            assertEquals("new-dataset", reader.read(client, reconnecting)!!.datasetId)
            assertEquals("MKCOL", server.takeRequest().method)
            assertEquals("GET", server.takeRequest().method)
            val create = server.takeRequest()
            assertEquals("PUT", create.method)
            assertEquals("*", create.getHeader("If-None-Match"))
            assertEquals("GET", server.takeRequest().method)
        }
    }

    @Test fun anotherDevicesNewDatasetMustBePreviewedInsteadOfReplaced() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody(manifest("other-dataset")).setHeader("ETag", "\"v2\"")) }
            server.start()
            val old = binding(server)
            val reader = WebDavManifestReader(Json)
            val recovery = try {
                reader.read(WebDavClient(old, ""), old)
                error("Expected changed dataset")
            } catch(error: WebDavRecoveryRequiredException) { error.recovery }
            assertEquals(WebDavRecoveryReason.REPLACED_DATASET, recovery.reason)
            val reconnecting = old.withoutDatasetBinding(recovery)
            assertEquals("other-dataset", reader.read(WebDavClient(reconnecting, ""), reconnecting)!!.datasetId)
            repeat(2) { assertEquals("GET", server.takeRequest().method) }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun staleDialogCannotClearANewerBinding() {
        val old = WebDavConfig(generation = 8, datasetId = "old-dataset")
        val recovery = WebDavRecovery(old.generation, old.datasetId!!, WebDavRecoveryReason.MISSING_DATASET)
        val changed = listOf(old.copy(generation = 9), old.copy(datasetId = "new-dataset"), old.copy(datasetId = null))
        changed.forEach {
            assertFalse(recovery.matches(it))
            assertThrows(WebDavConfigChangedException::class.java) { it.withoutDatasetBinding(recovery) }
        }
    }

    @Test fun authenticationAndServiceErrorsDoNotOfferDatasetReset() = runBlocking {
        for(code in listOf(401, 403, 503)) MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(code))
            server.start()
            val binding = binding(server)
            try { WebDavManifestReader(Json).read(WebDavClient(binding, ""), binding); fail("Expected HTTP failure") }
            catch(error: WebDavException) { assertEquals(code, error.statusCode) }
            assertEquals(1, server.requestCount)
        }
    }
}
