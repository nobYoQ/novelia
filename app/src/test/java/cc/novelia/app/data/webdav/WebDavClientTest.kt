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
            assertTrue(deleted.path!!.startsWith("/dav/novelia-sync/.novelia-probe-"))
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
                if(request.method != "MKCOL") assertTrue(request.path!!.contains(".novelia-probe-"))
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
}
