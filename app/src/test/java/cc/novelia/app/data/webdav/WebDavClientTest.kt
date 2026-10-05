package cc.novelia.app.data.webdav

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class WebDavClientTest {
    private fun client(server: MockWebServer, folder: String = "novelia-sync") = WebDavClient(
        WebDavConfig(endpoint = server.url("/dav/").toString(), username = "reader", folder = folder, allowInsecureHttp = true), "test-password")

    @Test fun connectionProbeVerifiesConcurrencyAndCleansOnlyItsFile() = runBlocking {
        MockWebServer().use { server ->
            val files = mutableMapOf<String, Pair<String, String>>()
            val requested = mutableListOf<RecordedRequest>()
            var version = 0
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requested += request
                    val path = request.path!!
                    val current = files[path]
                    return when(request.method) {
                        "MKCOL" -> MockResponse().setResponseCode(201)
                        "GET" -> current?.let { MockResponse().setBody(it.first).setHeader("ETag", it.second) } ?: MockResponse().setResponseCode(404)
                        "PUT" -> {
                            if((request.getHeader("If-None-Match") == "*" && current != null) ||
                                (request.getHeader("If-Match") != null && request.getHeader("If-Match") != current?.second)) {
                                MockResponse().setResponseCode(412)
                            } else {
                                val next = request.body.readUtf8() to "\"v${++version}\""
                                files[path] = next
                                MockResponse().setResponseCode(if(current == null) 201 else 204).setHeader("ETag", next.second)
                            }
                        }
                        "DELETE" -> { files.remove(path); MockResponse().setResponseCode(204) }
                        else -> MockResponse().setResponseCode(405)
                    }
                }
            }
            server.start()
            client(server).testConnection()
            assertTrue(files.isEmpty())
            val deleted = requested.single { it.method == "DELETE" }
            assertTrue(deleted.path!!.startsWith("/dav/novelia-sync/novelia-probe-"))
            assertNotNull(deleted.getHeader("If-Match"))
            assertTrue(requested.all { it.getHeader("Cookie") == null })
            assertTrue(requested.any { it.method == "PUT" && it.getHeader("If-None-Match") == "*" })
            assertEquals(2, requested.count { it.method == "PUT" && it.getHeader("If-Match") == "\"v1\"" })
        }
    }

    @Test fun badProbeDataStopsBeforeAnyRealSyncFile() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201))
            server.enqueue(MockResponse().setResponseCode(201))
            server.enqueue(MockResponse().setBody("wrong-data").setHeader("ETag", "\"v1\""))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(204))
            server.start()
            try { client(server).testConnection(); fail("Expected unsupported server") }
            catch(error: WebDavException) { assertEquals(WebDavFailure.UNSUPPORTED, error.failure) }
            repeat(server.requestCount) {
                val request = server.takeRequest()
                if(request.method != "MKCOL") assertTrue(request.path!!.contains("novelia-probe-"))
            }
        }
    }

    @Test fun serversIgnoringEitherWriteConditionAreRejected() = runBlocking {
        for(ignoredHeader in listOf("If-None-Match", "If-Match")) {
            MockWebServer().use { server ->
                var data: String? = null
                var version = 0
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when(request.method) {
                        "MKCOL" -> MockResponse().setResponseCode(201)
                        "GET" -> data?.let { MockResponse().setBody(it).setHeader("ETag", "\"v$version\"") } ?: MockResponse().setResponseCode(404)
                        "PUT" -> {
                            val rejectCreate = ignoredHeader != "If-None-Match" && request.getHeader("If-None-Match") == "*" && data != null
                            val rejectStale = ignoredHeader != "If-Match" && request.getHeader("If-Match")?.let { it != "\"v$version\"" } == true
                            if(rejectCreate || rejectStale) MockResponse().setResponseCode(412)
                            else { data = request.body.readUtf8(); version++; MockResponse().setResponseCode(201).setHeader("ETag", "\"v$version\"") }
                        }
                        "DELETE" -> { data = null; MockResponse().setResponseCode(204) }
                        else -> MockResponse().setResponseCode(405)
                    }
                }
                server.start()
                try { client(server).testConnection(); fail("Expected rejected write condition") }
                catch(error: WebDavException) { assertEquals(WebDavFailure.UNSUPPORTED, error.failure) }
                assertNull(data)
            }
        }
    }

    @Test fun conditionalGetAndPutExposeConcurrentChange() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(304))
            server.enqueue(MockResponse().setResponseCode(412))
            server.start()
            val c = client(server)
            assertTrue(c.get("settings.json", "\"v1\"")!!.notModified)
            assertEquals("\"v1\"", server.takeRequest().getHeader("If-None-Match"))
            try { c.put("settings.json", "{}".toByteArray(), "\"v1\""); fail("Expected conflict") }
            catch(error: WebDavException) { assertEquals(WebDavFailure.CONFLICT, error.failure); assertEquals(412, error.statusCode) }
            assertEquals("\"v1\"", server.takeRequest().getHeader("If-Match"))
        }
    }

    @Test fun clientRejectionsDoNotRetryWhileRateLimitsAndTimeoutsCanRetry() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(422))
            server.enqueue(MockResponse().setResponseCode(429))
            server.enqueue(MockResponse().setResponseCode(408))
            server.start()
            val c = client(server)
            for((code, expected, retryable) in listOf(Triple(422, WebDavFailure.INVALID_DATA, false), Triple(429, WebDavFailure.SERVER, true), Triple(408, WebDavFailure.NETWORK, true))) {
                try { c.get("settings.json"); fail("Expected rejected response") }
                catch(error: WebDavException) {
                    assertEquals(code, error.statusCode)
                    assertEquals(expected, error.failure)
                    assertEquals(retryable, retryWebDavFailure(error))
                }
            }
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun redirectsNeverForwardCredentialsAcrossOrigins() = runBlocking {
        MockWebServer().use { server -> MockWebServer().use { other ->
            server.start(); other.start()
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/dav/novelia-sync/settings.json")))
            try { client(server).get("settings.json"); fail("Expected rejected redirect") }
            catch(error: WebDavException) { assertEquals(WebDavFailure.PERMISSION, error.failure) }
            assertNull(other.takeRequest(100, TimeUnit.MILLISECONDS))
        } }
    }

    @Test fun redirectsCannotWriteOutsideConfiguredPath() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/dav/outside/settings.json")))
            try { client(server).put("settings.json", "{}".toByteArray(), createOnly = true); fail("Expected rejected path redirect") }
            catch(error: WebDavException) { assertEquals(WebDavFailure.PERMISSION, error.failure) }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun slashNormalizationKeepsConditionalWriteAndCredentialsAtSameOrigin() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/dav/novelia-sync/settings.json/")))
            server.enqueue(MockResponse().setResponseCode(204).setHeader("ETag", "\"v2\""))
            assertEquals("\"v2\"", client(server).put("settings.json", "{}".toByteArray(), "\"v1\""))
            val original = server.takeRequest()
            val redirected = server.takeRequest()
            assertEquals("PUT", redirected.method)
            assertEquals(original.getHeader("Authorization"), redirected.getHeader("Authorization"))
            assertEquals("\"v1\"", redirected.getHeader("If-Match"))
            assertEquals("{}", redirected.body.readUtf8())
        }
    }

    @Test fun sourceAuthenticationAndLoggingInterceptorsAreNeverInherited() = runBlocking {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("{}").setHeader("ETag", "\"v1\""))
            var called = false
            val original = OkHttpClient.Builder().addInterceptor { chain ->
                called = true
                chain.proceed(chain.request().newBuilder().header("Cookie", "source-cookie").header("Authorization", "Bearer source-authentication").build())
            }.build()
            val isolated = WebDavClient(WebDavConfig(endpoint = server.url("/dav/").toString(), username = "reader", allowInsecureHttp = true), "test-password", original)
            isolated.get("settings.json")
            val request = server.takeRequest()
            assertFalse(called)
            assertNull(request.getHeader("Cookie"))
            assertTrue(request.getHeader("Authorization")!!.startsWith("Basic "))
        }
    }

    @Test fun putWithoutReturnedVersionReadsBackAndChecksContent() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201))
            server.enqueue(MockResponse().setBody("{}").setHeader("ETag", "\"v1\""))
            server.start()
            assertEquals("\"v1\"", client(server).put("settings.json", "{}".toByteArray(), createOnly = true))
        }
    }

    @Test fun bareResponseVersionsAreQuotedForConditionalWrites() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("old").setHeader("ETag", "v1"))
            server.enqueue(MockResponse().setResponseCode(204).setHeader("ETag", "v2"))
            server.start()
            val c = client(server)
            val read = c.get("settings.json")!!
            assertEquals("\"v1\"", read.etag)
            assertEquals("\"v2\"", c.put("settings.json", "new".toByteArray(), read.etag))
            assertEquals("identity", server.takeRequest().getHeader("Accept-Encoding"))
            assertEquals("\"v1\"", server.takeRequest().getHeader("If-Match"))
        }
    }

    private fun propertyResponse(etag: String = "&quot;v2&quot;") = MockResponse().setResponseCode(207).setBody(
        """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/novelia-sync/settings.json</d:href><d:propstat><d:prop><d:getetag>$etag</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""")

    @Test fun propertyVersionIsPairedWithFreshConditionalBodyInsteadOfPreviousRead() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("old"))
            server.enqueue(propertyResponse())
            server.enqueue(MockResponse().setBody("new"))
            server.enqueue(MockResponse().setResponseCode(412))
            server.start()
            val read = client(server).get("settings.json")!!
            assertEquals("new", read.data.toString(Charsets.UTF_8))
            assertEquals("\"v2\"", read.etag)
            assertEquals("GET", server.takeRequest().method)
            val property = server.takeRequest()
            assertEquals("PROPFIND", property.method)
            assertEquals("0", property.getHeader("Depth"))
            assertTrue(property.body.readUtf8().contains("getetag"))
            assertEquals("\"v2\"", server.takeRequest().getHeader("If-Match"))
            assertTrue(server.takeRequest().getHeader("If-Match")!!.startsWith("\"novelia-absent-"))
        }
    }

    @Test fun propertyFallbackRejectsWeakVersionIgnoredReadConditionAndRaces() = runBlocking {
        for(scenario in listOf("weak", "ignored", "race")) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("old").setHeader("ETag", "W/\"compressed\""))
                server.enqueue(propertyResponse(if(scenario == "weak") "W/&quot;v2&quot;" else "&quot;v2&quot;"))
                if(scenario != "weak") server.enqueue(if(scenario == "race") MockResponse().setResponseCode(412) else MockResponse().setBody("new"))
                if(scenario == "ignored") server.enqueue(MockResponse().setBody("new"))
                server.start()
                try { client(server).get("settings.json"); fail("Expected rejected $scenario read") }
                catch(error: WebDavException) { assertEquals(if(scenario == "race") WebDavFailure.CONFLICT else WebDavFailure.UNSUPPORTED, error.failure) }
                repeat(server.requestCount) { assertTrue(server.takeRequest().method in listOf("GET", "PROPFIND")) }
            }
        }
    }

    @Test fun missingPutVersionDoesNotConfirmContentChangedByAnotherDevice() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setBody("{\"remote\":true}").setHeader("ETag", "\"v2\""))
            server.start()
            try { client(server).put("settings.json", "{}".toByteArray(), "\"v1\""); fail("Expected concurrent content change") }
            catch(error: WebDavException) { assertEquals(WebDavFailure.CONFLICT, error.failure) }
        }
    }

    @Test fun weakVersionCannotBeUsedForAutomaticWriting() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            try { client(server).put("settings.json", "{}".toByteArray(), "W/\"v1\""); fail("Expected rejected weak version") }
            catch(error: WebDavException) { assertEquals(WebDavFailure.UNSUPPORTED, error.failure) }
            assertEquals(0, server.requestCount)
        }
        assertFalse(WebDavClient.isStrongEtag("\"bad\r\nversion\""))
        assertFalse(WebDavClient.isStrongEtag("bare"))
        assertTrue(WebDavClient.isStrongEtag("\"opaque-tag\""))
    }

    @Test fun oversizedResponsesAreRejectedBeforeReadingBody() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Length", WebDavClient.MAX_RESPONSE_BYTES + 1))
            server.start()
            try { client(server).get("notes.json"); fail("Expected too-large response") }
            catch(error: WebDavException) { assertEquals(WebDavFailure.INVALID_DATA, error.failure) }
        }
    }

    @Test fun endpointRejectsEmbeddedCredentialsQueriesAndTraversal() {
        listOf("https://user:password@example.test/dav/", "https://example.test/dav/?token=value", "https://example.test/dav/../other/", "https://example.test/dav/%2e%2e/other/").forEach { endpoint ->
            assertThrows(IllegalArgumentException::class.java) { WebDavPaths.endpoint(WebDavConfig(endpoint = endpoint)) }
        }
        listOf("../outside", "a/%2e%2e", "a\\b", "a/./b").forEach { folder ->
            assertThrows(IllegalArgumentException::class.java) { WebDavPaths.folderSegments(folder) }
        }
        assertThrows(IllegalArgumentException::class.java) { WebDavPaths.endpoint(WebDavConfig(endpoint = "http://example.test/dav/")) }
    }

    @Test fun nestedChineseDirectoryIsCreatedSegmentBySegment() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201)); server.enqueue(MockResponse().setResponseCode(201)); server.start()
            client(server, "同步/小说").ensureDirectory()
            val first = server.takeRequest().requestUrl!!
            val second = server.takeRequest().requestUrl!!
            assertEquals(listOf("dav", "同步", ""), first.pathSegments)
            assertEquals(listOf("dav", "同步", "小说", ""), second.pathSegments)
        }
    }

    private fun collectionResponse(path: String = "/dav/novelia-sync/", status: Int = 200) = MockResponse().setResponseCode(207).setBody(
        """<d:multistatus xmlns:d="DAV:"><d:response><d:href>$path</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 $status Response</d:status></d:propstat></d:response></d:multistatus>""")

    @Test fun existingCollectionIsVerifiedAfterNonstandardCreateResponse() = runBlocking {
        for(status in listOf(403, 404, 405, 409)) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(status))
                server.enqueue(collectionResponse())
                server.start()
                client(server).ensureDirectory()
                assertEquals("MKCOL", server.takeRequest().method)
                val check = server.takeRequest()
                assertEquals("PROPFIND", check.method)
                assertEquals("0", check.getHeader("Depth"))
                assertEquals("/dav/novelia-sync/", check.path)
            }
        }
    }

    @Test fun unrelatedOrFailedCollectionPropertiesCannotConfirmDirectoryExists() = runBlocking {
        for(response in listOf(collectionResponse("/dav/other/"), collectionResponse(status = 404))) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(409))
                server.enqueue(response)
                server.start()
                try { client(server).ensureDirectory(); fail("Expected invalid target collection") }
                catch(error: WebDavException) { assertEquals(WebDavFailure.UNSUPPORTED, error.failure) }
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test fun missingDirectoryAndFileUploadErrorsDescribeTheirOwnOperation() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(409))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(404))
            server.start()
            val c = client(server)
            try { c.ensureDirectory(); fail("Expected unavailable base directory") }
            catch(error: WebDavException) {
                assertEquals(WebDavFailure.NOT_FOUND, error.failure)
                assertTrue(error.message!!.contains("MKCOL 409"))
            }
            try { c.put("settings.json", "{}".toByteArray(), createOnly = true); fail("Expected unavailable upload location") }
            catch(error: WebDavException) {
                assertEquals(WebDavFailure.NOT_FOUND, error.failure)
                assertTrue(error.message!!.contains("PUT 404"))
                assertFalse(error.message!!.contains("父目录不存在"))
            }
        }
    }

    @Test fun permissionFailureWhenCheckingExistingDirectoryIsNotIgnored() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setResponseCode(403))
            server.start()
            try { client(server).ensureDirectory(); fail("Expected denied collection access") }
            catch(error: WebDavException) {
                assertEquals(WebDavFailure.PERMISSION, error.failure)
                assertTrue(error.message!!.contains("PROPFIND 403"))
            }
            assertEquals(2, server.requestCount)
        }
    }
}
