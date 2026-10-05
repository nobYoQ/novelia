package cc.novelia.app.data.webdav

import java.net.InetAddress
import java.net.Proxy
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

/** 坚果云地址只经测试 DNS 连到回环 MockWebServer，不访问真实服务或使用账号。 */
class WebDavNutcloudCompatibilityTest {
    private fun client(server: MockWebServer): WebDavClient {
        val localTransport = OkHttpClient.Builder().dns(object : Dns {
            override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1"))
        }).proxy(Proxy.NO_PROXY).build()
        return WebDavClient(WebDavConfig(endpoint = "http://dav.jianguoyun.com:${server.port}/dav/", allowInsecureHttp = true), "", localTransport)
    }

    private class Service(
        private val propertyVersions: Boolean = false,
        private val invalidProperty: Boolean = false,
        private val ignoreOverwrite: Boolean = false,
        private val ignoreIfMatch: Boolean = false,
        private val rejectMove: Boolean = false,
        private val absoluteDestinationFailure: Int? = null,
        private val relativeDestinationFailure: Int? = null,
        private val corruptSourceAfterFailedMove: Boolean = false,
        private val rejectTemporaryUploads: Boolean = false,
        private val rejectHiddenMoves: Boolean = false,
        private val requireBinaryUploads: Boolean = false,
        private val requireMoveParentRefresh: Boolean = false,
        private val denyParentRefresh: Boolean = false,
        private val duplicateMoveStatus: Int = 412,
        private val overwriteBeforeRejecting: Boolean = false
    ) : Dispatcher() {
        val files = mutableMapOf<String, Pair<String, String>>()
        val requests = mutableListOf<RecordedRequest>()
        private var version = 0
        private var pendingParentRefresh: String? = null
        private val refreshedMoveSources = mutableSetOf<String>()

        @Synchronized override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request
            val path = request.requestUrl!!.encodedPath
            val current = files[path]
            // 真实坚果云条件请求比较裸 ETag，带引号的当前版本同样会被拒绝。
            val match = request.getHeader("If-Match")?.let { "\"$it\"" }
            return when(request.method) {
                "MKCOL" -> if(denyParentRefresh && pendingParentRefresh != null) MockResponse().setResponseCode(403) else {
                    pendingParentRefresh?.let { refreshedMoveSources += it }
                    pendingParentRefresh = null
                    MockResponse().setResponseCode(201)
                }
                "GET" -> when {
                    current == null -> MockResponse().setResponseCode(404)
                    match != null && match != current.second -> MockResponse().setResponseCode(412)
                    request.getHeader("If-None-Match")?.let { "\"$it\"" } == current.second -> MockResponse().setResponseCode(304)
                    else -> MockResponse().setBody(current.first).apply { if(!propertyVersions) setHeader("ETag", current.second.trim('"')) }
                }
                "PROPFIND" -> if(denyParentRefresh && pendingParentRefresh != null) MockResponse().setResponseCode(403)
                else if(current == null) MockResponse().setResponseCode(404) else MockResponse().setResponseCode(207).setBody(
                    """<d:multistatus xmlns:d="DAV:"><d:response><d:href>$path</d:href><d:propstat><d:prop><d:getetag>${if(invalidProperty) "W/" else ""}${current.second}</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""")
                // 故意忽略 If-None-Match；共享目标必须经禁止覆盖的 MOVE 创建。
                "PUT" -> if(rejectTemporaryUploads && path.endsWith(".tmp")) MockResponse().setResponseCode(404)
                else if(requireBinaryUploads && request.getHeader("Content-Type") != "application/octet-stream") MockResponse().setResponseCode(415)
                else if(!ignoreIfMatch && match != null && match != current?.second) MockResponse().setResponseCode(412) else {
                    val next = request.body.readUtf8() to "\"v${++version}\""
                    files[path] = next
                    MockResponse().setResponseCode(if(current == null) 201 else 204).apply { if(!propertyVersions) setHeader("ETag", next.second.trim('"')) }
                }
                "MOVE" -> {
                    val destinationHeader = request.getHeader("Destination")!!
                    val destination = request.requestUrl!!.resolve(destinationHeader)!!.encodedPath
                    when {
                        rejectMove -> MockResponse().setResponseCode(405)
                        current == null -> MockResponse().setResponseCode(404)
                        rejectHiddenMoves && (path.substringAfterLast('/').startsWith('.') || destination.substringAfterLast('/').startsWith('.')) -> MockResponse().setResponseCode(409)
                        requireMoveParentRefresh && path !in refreshedMoveSources -> {
                            pendingParentRefresh = path
                            MockResponse().setResponseCode(409)
                        }
                        absoluteDestinationFailure != null && destinationHeader.startsWith("http") -> {
                            if(corruptSourceAfterFailedMove) files[path] = "changed" to "\"changed\""
                            MockResponse().setResponseCode(absoluteDestinationFailure)
                        }
                        relativeDestinationFailure != null && destinationHeader.startsWith("/") -> MockResponse().setResponseCode(relativeDestinationFailure)
                        !ignoreOverwrite && request.getHeader("Overwrite") == "F" && destination in files -> {
                            if(overwriteBeforeRejecting) files[destination] = current
                            MockResponse().setResponseCode(duplicateMoveStatus)
                        }
                        else -> { files[destination] = current; files.remove(path); MockResponse().setResponseCode(201) }
                    }
                }
                "DELETE" -> if(match != null && current != null && match != current.second) MockResponse().setResponseCode(412)
                    else { files.remove(path); MockResponse().setResponseCode(204) }
                else -> MockResponse().setResponseCode(405)
            }
        }
    }

    @Test fun emptyServicePassesProbeWithoutConditionalPutCreationSupport() = runBlocking {
        for(properties in listOf(false, true)) {
            MockWebServer().use { server ->
                val service = Service(propertyVersions = properties)
                server.dispatcher = service
                server.start()
                client(server).testConnection()
                assertTrue(service.files.isEmpty())
                assertEquals(2, service.requests.count { it.method == "MOVE" })
                assertTrue(service.requests.filter { it.method == "MOVE" }.all { it.getHeader("Overwrite") == "F" })
                assertTrue(service.requests.filter { it.method == "PUT" && it.getHeader("If-Match") == null }
                    .all { it.path!!.contains("novelia-upload-") })
                assertTrue(service.requests.filter { it.method == "DELETE" }.all {
                    it.path!!.contains("novelia-upload-") || it.path!!.contains("novelia-probe-")
                })
                assertTrue(service.requests.none { it.path!!.contains("manifest.json") })
            }
        }
    }

    @Test fun twoNewDevicesCannotReplaceEachOthersInitialManifest() = runBlocking {
        MockWebServer().use { server ->
            val service = Service()
            server.dispatcher = service
            server.start()
            val a = client(server)
            val b = client(server)
            val outcomes = withTimeout(10_000) {
                coroutineScope {
                    listOf(async { runCatching { a.put("manifest.json", "device-a".toByteArray(), createOnly = true) } },
                        async { runCatching { b.put("manifest.json", "device-b".toByteArray(), createOnly = true) } }).map { it.await() }
                }
            }
            assertEquals(1, outcomes.count { it.isSuccess })
            val conflict = outcomes.single { it.isFailure }.exceptionOrNull() as WebDavException
            assertEquals(WebDavFailure.CONFLICT, conflict.failure)
            assertEquals(412, conflict.statusCode)
            assertEquals(1, service.files.size)
            assertTrue(service.files.values.single().first in listOf("device-a", "device-b"))
            assertTrue(service.requests.filter { it.method == "PUT" }.all { it.path!!.contains("novelia-upload-") })
        }
    }

    @Test fun ignoredMoveOverwriteOrStaleUpdateIsRejectedAndProbeIsCleaned() = runBlocking {
        for(service in listOf(Service(ignoreOverwrite = true), Service(ignoreIfMatch = true))) {
            MockWebServer().use { server ->
                service.files["/dav/novelia-sync/keep.json"] = "existing-user-data" to "\"keep\""
                server.dispatcher = service
                server.start()
                try { client(server).testConnection(); fail("Expected rejected concurrency protection") }
                catch(error: WebDavException) { assertEquals(WebDavFailure.UNSUPPORTED, error.failure) }
                assertEquals(mapOf("/dav/novelia-sync/keep.json" to ("existing-user-data" to "\"keep\"")), service.files)
            }
        }
    }

    @Test fun failedMoveCleansOnlyItsTemporaryUploadAndPreservesDestination() = runBlocking {
        MockWebServer().use { server ->
            val service = Service(rejectMove = true)
            service.files["/dav/novelia-sync/settings.json"] = "existing" to "\"v0\""
            server.dispatcher = service
            server.start()
            try { client(server).put("settings.json", "new".toByteArray(), createOnly = true); fail("Expected failed MOVE") }
            catch(error: WebDavException) { assertEquals(WebDavFailure.UNSUPPORTED, error.failure) }
            assertEquals(mapOf("/dav/novelia-sync/settings.json" to ("existing" to "\"v0\"")), service.files)
            assertTrue(service.requests.filter { it.method == "DELETE" }.all { it.path!!.contains("novelia-upload-") })
        }
    }

    @Test fun missingUsableVersionAfterFirstCreationStillCleansItsProbe() = runBlocking {
        MockWebServer().use { server ->
            val service = Service(propertyVersions = true, invalidProperty = true)
            service.files["/dav/novelia-sync/keep.json"] = "existing" to "\"v0\""
            server.dispatcher = service
            server.start()
            try { client(server).testConnection(); fail("Expected unavailable strong version") }
            catch(error: WebDavException) { assertEquals(WebDavFailure.UNSUPPORTED, error.failure) }
            assertEquals(mapOf("/dav/novelia-sync/keep.json" to ("existing" to "\"v0\"")), service.files)
            assertTrue(service.requests.filter { it.method == "DELETE" }.all {
                it.path!!.contains("novelia-upload-") || it.path!!.contains("novelia-probe-")
            })
        }
    }

    @Test fun absoluteDestinationMappingFailureCanUseSameOriginPathWithoutLosingOverwriteProtection() = runBlocking {
        for(status in listOf(404, 409)) {
            MockWebServer().use { server ->
                val service = Service(absoluteDestinationFailure = status)
                server.dispatcher = service
                server.start()
                client(server).testConnection()
                assertTrue(service.files.isEmpty())
                val moves = service.requests.filter { it.method == "MOVE" }
                assertEquals(4, moves.size)
                assertTrue(moves.all { it.getHeader("Overwrite") == "F" })
                assertEquals(if(status == 409) 1 else 2, moves.count { it.getHeader("Destination")!!.startsWith("/dav/novelia-sync/") })
                assertTrue(service.requests.filter { it.method == "PUT" && it.getHeader("If-Match") == null }
                    .all { it.path!!.contains("novelia-upload-") })
            }
        }
    }

    @Test fun relativeDestinationRetryCannotOverwriteAnotherDevicesNewFile() = runBlocking {
        MockWebServer().use { server ->
            val service = Service(absoluteDestinationFailure = 404)
            server.dispatcher = service
            server.start()
            val c = client(server)
            c.put("manifest.json", "winner".toByteArray(), createOnly = true)
            try { c.put("manifest.json", "loser".toByteArray(), createOnly = true); fail("Expected concurrent creation rejected") }
            catch(error: WebDavException) { assertEquals(412, error.statusCode) }
            assertEquals(1, service.files.size)
            assertEquals("winner", service.files.values.single().first)
            assertTrue(service.requests.filter { it.method == "MOVE" }.all { it.getHeader("Overwrite") == "F" })
        }
    }

    @Test fun changedTemporarySourceIsNotRetriedAtDestination() = runBlocking {
        MockWebServer().use { server ->
            val service = Service(absoluteDestinationFailure = 404, corruptSourceAfterFailedMove = true)
            server.dispatcher = service
            server.start()
            try { client(server).put("manifest.json", "new".toByteArray(), createOnly = true); fail("Expected changed source rejected") }
            catch(error: WebDavException) { assertEquals(WebDavFailure.CONFLICT, error.failure) }
            assertEquals(1, service.requests.count { it.method == "MOVE" })
            assertTrue(service.files.isEmpty())
        }
    }

    @Test fun unresolvedMoveIsReportedAsMoveInsteadOfDirectoryCreationFailure() = runBlocking {
        MockWebServer().use { server ->
            val service = Service(absoluteDestinationFailure = 404, relativeDestinationFailure = 404)
            server.dispatcher = service
            server.start()
            try { client(server).put("manifest.json", "new".toByteArray(), createOnly = true); fail("Expected failed destination mapping") }
            catch(error: WebDavException) {
                assertEquals(WebDavFailure.NOT_FOUND, error.failure)
                assertTrue(error.message!!.contains("MOVE 404"))
                assertFalse(error.message!!.contains("父目录不存在"))
            }
            assertTrue(service.files.isEmpty())
        }
    }

    @Test fun connectionProbeAvoidsFileSuffixesFilteredByTheService() = runBlocking {
        MockWebServer().use { server ->
            val service = Service(rejectTemporaryUploads = true)
            server.dispatcher = service
            server.start()
            client(server).testConnection()
            assertTrue(service.files.isEmpty())
            assertTrue(service.requests.filter { it.method == "PUT" }.none { it.path!!.endsWith(".tmp") })
            assertTrue(service.requests.any { it.method == "PUT" && it.path!!.contains("novelia-upload-") && it.path!!.endsWith(".cache") })
        }
    }

    @Test fun probeAndFirstCreationUseOrdinaryNamesAndBinaryUploads() = runBlocking {
        MockWebServer().use { server ->
            val service = Service(rejectHiddenMoves = true, requireBinaryUploads = true)
            server.dispatcher = service
            server.start()
            val c = client(server)
            c.testConnection()
            c.put("manifest.json", "dataset".toByteArray(), createOnly = true)
            assertEquals("dataset", service.files["/dav/novelia-sync/manifest.json"]!!.first)
            assertEquals(1, service.files.size)
            val uploads = service.requests.filter { it.method == "PUT" }
            assertTrue(uploads.all { it.getHeader("Content-Type") == "application/octet-stream" })
            assertTrue(uploads.all { !it.requestUrl!!.pathSegments.last().startsWith('.') })
            assertTrue(uploads.filter { it.getHeader("If-Match") == null }.all { it.getHeader("If-None-Match") == null })
            assertTrue(service.requests.filter { it.method == "MOVE" }.all {
                it.getHeader("Overwrite") == "F" && !it.getHeader("Destination")!!.substringAfterLast('/').startsWith('.')
            })
        }
    }

    @Test fun moveConflictRefreshesParentBeforeBoundedRetryWithSameDestination() = runBlocking {
        MockWebServer().use { server ->
            val service = Service(requireMoveParentRefresh = true)
            server.dispatcher = service
            server.start()
            client(server).testConnection()
            assertTrue(service.files.isEmpty())
            val moves = service.requests.filter { it.method == "MOVE" }
            assertEquals(3, moves.size)
            moves.take(2).chunked(2).forEach { (original, retried) ->
                assertEquals(original.path, retried.path)
                assertEquals(original.getHeader("Destination"), retried.getHeader("Destination"))
                assertEquals("F", retried.getHeader("Overwrite"))
                val between = service.requests.subList(service.requests.indexOf(original) + 1, service.requests.indexOf(retried))
                assertTrue(between.any { it.method == "GET" && it.path == original.path })
                assertTrue(between.any { it.method == "MKCOL" && it.path == "/dav/novelia-sync/" })
            }
            assertTrue(service.requests.none { it.method == "DELETE" && it.path!!.endsWith("manifest.json") })
        }
    }

    @Test fun parentRefreshCannotReplaceAnotherDevicesNewFile() = runBlocking {
        MockWebServer().use { server ->
            val service = Service(requireMoveParentRefresh = true)
            server.dispatcher = service
            server.start()
            val c = client(server)
            c.put("manifest.json", "winner".toByteArray(), createOnly = true)
            try { c.put("manifest.json", "loser".toByteArray(), createOnly = true); fail("Expected rejected creation") }
            catch(error: WebDavException) { assertEquals(409, error.statusCode) }
            assertEquals("winner", service.files.values.single().first)
            assertTrue(service.requests.filter { it.method == "MOVE" }.all { it.getHeader("Overwrite") == "F" })
            assertTrue(service.requests.filter { it.method == "DELETE" }.all { it.path!!.contains("novelia-upload-") })
        }
    }

    @Test fun deniedParentRefreshStopsBeforeAnotherMoveAndCleansOnlyTemporarySource() = runBlocking {
        MockWebServer().use { server ->
            val service = Service(requireMoveParentRefresh = true, denyParentRefresh = true)
            service.files["/dav/novelia-sync/keep.json"] = "keep" to "\"v0\""
            server.dispatcher = service
            server.start()
            try { client(server).put("settings.json", "new".toByteArray(), createOnly = true); fail("Expected denied parent refresh") }
            catch(error: WebDavException) { assertEquals(WebDavFailure.PERMISSION, error.failure) }
            assertEquals(1, service.requests.count { it.method == "MOVE" })
            assertEquals(mapOf("/dav/novelia-sync/keep.json" to ("keep" to "\"v0\"")), service.files)
        }
    }

    @Test fun persistentMissingDestinationConflictIsBoundedAndCleansTemporarySource() = runBlocking {
        MockWebServer().use { server ->
            val service = Service(absoluteDestinationFailure = 409, relativeDestinationFailure = 409)
            server.dispatcher = service
            server.start()
            try { client(server).put("settings.json", "new".toByteArray(), createOnly = true); fail("Expected unresolved MOVE") }
            catch(error: WebDavException) { assertTrue(error.message!!.contains("MOVE 409")) }
            assertEquals(3, service.requests.count { it.method == "MOVE" })
            assertEquals(1, service.requests.count { it.method == "MKCOL" })
            assertTrue(service.files.isEmpty())
            assertTrue(service.requests.filter { it.method == "DELETE" }.all { it.path!!.contains("novelia-upload-") })
        }
    }

    @Test fun actualNutcloudDuplicate409PassesProbeAndDoesNotRetryMoveOrRecreateDirectory() = runBlocking {
        MockWebServer().use { server ->
            val service = Service(duplicateMoveStatus = 409)
            service.files["/dav/novelia-sync/keep.json"] = "keep" to "\"v0\""
            server.dispatcher = service
            server.start()
            client(server).testConnection()
            assertEquals(2, service.requests.count { it.method == "MOVE" })
            assertEquals(1, service.requests.count { it.method == "MKCOL" })
            assertEquals(mapOf("/dav/novelia-sync/keep.json" to ("keep" to "\"v0\"")), service.files)
            assertTrue(service.requests.filter { it.getHeader("If-Match") != null }.all { !it.getHeader("If-Match")!!.startsWith('"') })
        }
    }

    @Test fun duplicate409FromAnotherDeviceIsAConflictAndKeepsItsInitialManifest() = runBlocking {
        MockWebServer().use { server ->
            val service = Service(duplicateMoveStatus = 409)
            server.dispatcher = service
            server.start()
            val outcomes = withTimeout(10_000) {
                coroutineScope {
                    listOf(async { runCatching { client(server).put("manifest.json", "device-a".toByteArray(), createOnly = true) } },
                        async { runCatching { client(server).put("manifest.json", "device-b".toByteArray(), createOnly = true) } }).map { it.await() }
                }
            }
            assertEquals(1, outcomes.count { it.isSuccess })
            val rejected = outcomes.single { it.isFailure }.exceptionOrNull() as WebDavException
            assertEquals(WebDavFailure.CONFLICT, rejected.failure)
            assertEquals(409, rejected.statusCode)
            assertEquals(2, service.requests.count { it.method == "MOVE" })
            assertEquals(1, service.files.size)
            assertTrue(service.files.values.single().first in listOf("device-a", "device-b"))
        }
    }

    @Test fun canonicalVersionsUseBareConditionsForNutcloudUpdatesAndCachedReads() = runBlocking {
        MockWebServer().use { server ->
            val service = Service(duplicateMoveStatus = 409)
            service.files["/dav/novelia-sync/settings.json"] = "before" to "\"v0\""
            server.dispatcher = service
            server.start()
            val c = client(server)
            val old = c.get("settings.json")!!.etag!!
            assertEquals("\"v0\"", old)
            val next = c.put("settings.json", "after".toByteArray(), old)
            assertEquals("\"v1\"", next)
            assertTrue(c.get("settings.json", next)!!.notModified)
            try { c.put("settings.json", "stale".toByteArray(), old); fail("Expected stale version rejected") }
            catch(error: WebDavException) { assertEquals(412, error.statusCode) }
            assertEquals("after", service.files.values.single().first)
            assertEquals(listOf("v0", "v0"), service.requests.filter { it.method == "PUT" }.map { it.getHeader("If-Match") })
            assertEquals("v1", service.requests.single { it.getHeader("If-None-Match") != null }.getHeader("If-None-Match"))
        }
    }

    @Test fun rejection409CannotHideAnOverwrittenOriginalDuringProbe() = runBlocking {
        MockWebServer().use { server ->
            val service = Service(duplicateMoveStatus = 409, overwriteBeforeRejecting = true)
            service.files["/dav/novelia-sync/keep.json"] = "keep" to "\"v0\""
            server.dispatcher = service
            server.start()
            try { client(server).testConnection(); fail("Expected fake rejection detected") }
            catch(error: WebDavException) { assertEquals(WebDavFailure.UNSUPPORTED, error.failure) }
            assertEquals(mapOf("/dav/novelia-sync/keep.json" to ("keep" to "\"v0\"")), service.files)
        }
    }
}
