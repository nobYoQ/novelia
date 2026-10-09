package cc.novelia.app.data.webdav

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class WebDavTrafficTest {
    /** 通用服务：标准条件请求；分别覆盖 GET/PUT 有 ETag 和只能从 PROPFIND 取得版本。 */
    @Test fun continuousEditsStayBoundedWithHeaderOrPropertyVersions() = runBlocking {
        for(propertyVersions in listOf(false, true)) {
            MockWebServer().use { server ->
                val files = (SyncDomain.entries.map { it.fileName } + "manifest.json").associateWith {
                    (if(it == "manifest.json") """{"format":"novelia-webdav","schemaVersion":1,"datasetId":"dataset"}""" else "initial") to "\"v0\""
                }.toMutableMap()
                var version = 0
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val path = request.requestUrl!!.encodedPath
                        val name = path.substringAfterLast('/')
                        val current = files[name] ?: return MockResponse().setResponseCode(404)
                        val match = request.getHeader("If-Match")
                        return when(request.method) {
                            "GET" -> when {
                                match != null && match != current.second -> MockResponse().setResponseCode(412)
                                request.getHeader("If-None-Match") == current.second -> MockResponse().setResponseCode(304)
                                else -> MockResponse().setBody(current.first).apply { if(!propertyVersions) setHeader("ETag", current.second) }
                            }
                            "PROPFIND" -> MockResponse().setResponseCode(207).setBody(
                                """<d:multistatus xmlns:d="DAV:"><d:response><d:href>$path</d:href><d:propstat><d:prop><d:getetag>${current.second}</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""")
                            "PUT" -> if(match != current.second) MockResponse().setResponseCode(412) else {
                                val next = request.body.readUtf8() to "\"v${++version}\""
                                files[name] = next
                                MockResponse().setResponseCode(204).apply { if(!propertyVersions) setHeader("ETag", next.second) }
                            }
                            else -> MockResponse().setResponseCode(405)
                        }
                    }
                }
                server.start()
                val binding = WebDavConfig(endpoint = server.url("/dav/").toString(), allowInsecureHttp = true, datasetId = "dataset")
                var now = 0L
                val policy = WebDavAutomaticPolicy { now }
                val manifest = WebDavManifestReader(Json)
                val versions = mutableMapOf<SyncDomain, String>()
                val dirty = setOf(SyncDomain.PROGRESS, SyncDomain.HISTORY)
                // 30 分钟、每十秒同时触发前台和 Worker；期间进度与历史持续变化。
                repeat(180) { tick ->
                    now = tick * 10_000L
                    repeat(2) {
                        val domains = policy.domains(binding, dirty)
                        if(domains.isNotEmpty()) {
                            val client = WebDavClient(binding, "")
                            manifest.read(client, binding)
                            for(domain in domains) {
                                val remote = client.get(domain.fileName, versions[domain])!!
                                versions[domain] = if(domain in dirty) client.put(domain.fileName,
                                    "edit-$tick".toByteArray(), etag = remote.etag) else remote.etag!!
                            }
                        }
                    }
                }
                assertEquals(if(propertyVersions) 259 else 115, server.requestCount)
                assertEquals(30, version)
            }
        }
    }
}
