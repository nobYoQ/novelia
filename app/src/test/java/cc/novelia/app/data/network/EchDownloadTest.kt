package cc.novelia.app.data.network

import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.storage.appJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class EchDownloadTest {
    @Test fun receivedFilenameHeadersPreserveUnicodeAndRepeatedValues() {
        val disposition = "attachment; filename=\"中文小说・第１巻 😀.epub\""
        val headers = decodeEchResponseHeaders(appJson.encodeToString(mapOf(
            "Content-Disposition" to listOf(disposition),
            "Content-Type" to listOf("application/epub+zip"),
            "Content-Length" to listOf("131073"),
            "Set-Cookie" to listOf("synthetic-first=1", "synthetic-second=2"),
            "Connection" to listOf("keep-alive"),
            "Transfer-Encoding" to listOf("chunked")
        )))
        assertEquals(disposition, headers["Content-Disposition"])
        assertEquals("application/epub+zip", headers["Content-Type"])
        assertEquals("131073", headers["Content-Length"])
        assertEquals(listOf("synthetic-first=1", "synthetic-second=2"), headers.values("Set-Cookie"))
        assertNull(headers["Connection"])
        assertNull(headers["Transfer-Encoding"])
    }

    @Test fun malformedHeaderNamesAndControlCharactersAreStillRejected() {
        for ((name, value) in listOf(
            "Bad\nName" to "value",
            "Content-Disposition" to "filename=\"正常.epub\"\r\nInjected: value",
            "Content-Disposition" to "bad${0.toChar()}value",
            "Content-Disposition" to "bad${127.toChar()}value"
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                decodeEchResponseHeaders(appJson.encodeToString(mapOf(name to listOf(value))))
            }
        }
    }

    @Test(timeout = 10_000) fun novelAndWenkuDownloadsFollowRedirectAndReadCompleteFilesWithUnicodeNames() = runBlocking {
        val cases = listOf(
            Triple(BookRef("syosetu", "test"), null, "txt"),
            Triple(BookRef("syosetu", "test"), null, "epub"),
            Triple(BookRef("wenku", "test"), "第１巻 中文.epub", "epub")
        )
        for ((ref, volume, format) in cases) {
            val filename = "zh.下载测试・日本語 😀.$format"
            val content = if (format == "txt") "下载正文・日本語\n".repeat(20_000).toByteArray()
                else ByteArray(196_613) { (it * 17).toByte() }
            val contentType = if (format == "txt") "text/plain; charset=utf-8" else "application/epub+zip"
            val requests = mutableListOf<Request>()
            val closed = mutableListOf<String>()
            val engine = object : EchEngine {
                override fun open(request: Request, timeouts: EchTimeouts): EchExchange {
                    requests += request
                    assertEquals(120_000L, timeouts.readMillis)
                    val redirect = request.url.encodedPath.startsWith("/api/")
                    val target = request.url.newBuilder().encodedPath("/files-temp/sample.$format").build()
                    val data = Buffer().apply { if (!redirect) write(content) }
                    val values = if (redirect) mapOf("Location" to listOf(target.toString())) else mapOf(
                        "Content-Disposition" to listOf("attachment; filename=\"$filename\""),
                        "Content-Type" to listOf(contentType),
                        "Content-Length" to listOf(content.size.toString())
                    )
                    return object : EchExchange {
                        override fun execute() = EchReply(if (redirect) 302 else 200, Protocol.HTTP_2,
                            decodeEchResponseHeaders(appJson.encodeToString(values)), if (redirect) 0 else content.size.toLong())
                        override fun read(maxBytes: Long) = data.readByteArray(minOf(maxBytes, data.size))
                        override fun cancel() { closed += request.url.encodedPath }
                    }
                }
            }
            val client = OkHttpClient.Builder().addInterceptor(EchInterceptor(engine, { true }))
                .addInterceptor { error("The test download must use ECH") }.build()
            val api = NoveliaApi(null, transport = client)
            val downloads = api.downloadTransport
            try {
                val url = api.downloadUrl(ref, volume, "zh", listOf("sakura", "gpt"), false, format, filename)
                val received = api.withAuthenticatedResponse(Request.Builder().url(url).build(), client = downloads) { response ->
                    assertEquals(200, response.code)
                    assertEquals("attachment; filename=\"$filename\"", response.header("Content-Disposition"))
                    assertEquals(contentType, response.body!!.contentType().toString())
                    assertEquals(content.size.toLong(), response.body!!.contentLength())
                    response.body!!.byteStream().use { it.readBytes() }
                }
                assertArrayEquals(content, received)
                assertEquals(2, requests.size)
                assertEquals(filename, requests.first().url.queryParameter("filename"))
                assertTrue(requests.first().url.encodedPath.startsWith(if (ref.isWenku) "/api/wenku/" else "/api/novel/"))
                assertEquals(requests.map { it.url.encodedPath }, closed)
            } finally {
                downloads.dispatcher.executorService.shutdownNow()
                client.dispatcher.executorService.shutdownNow()
            }
        }
    }
}
