package cc.novelia.app.data.network

import cc.novelia.app.data.auth.SessionBinding
import cc.novelia.app.data.auth.SessionChangedException
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class BookSourceTest {
    private fun sources() = BookSources(initialToken = "test-gateway-only")
    private fun response(request: Request, code: Int = 200, location: String? = null): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("test").body("ok".toResponseBody())
        .apply { location?.let { header("Location", it) } }.build()

    @Test fun mirrorRoutesAllAuthAndContentPathsAndPreservesEncodedQueries() {
        val sources = sources().apply { select(BookSource.XKVI) }
        val router = BookSourceInterceptor(sources)
        for(path in listOf("/api/novel", "/api/v1/auth/login", "/api/v1/auth/register", "/api/v1/auth/otp/request", "/api/v1/auth/refresh?app=n")) {
            val request = Request.Builder().url("https://n.novelia.cc$path").post("test-json".toRequestBody()).build()
            val prepared = router.prepare(request, sources.capture())
            assertEquals("book.xkvi.top", prepared.url.host)
            assertEquals(request.url.encodedPath, prepared.url.encodedPath)
            assertEquals(request.url.encodedQuery, prepared.url.encodedQuery)
            assertSame(request.body, prepared.body)
            assertEquals(if(path == "/api/novel") "accessToken=test-gateway-only" else null, prepared.header("Cookie"))
        }
        val request = Request.Builder().url("https://auth.novelia.cc/api/v1/auth/refresh?app=n")
            .header("Cookie", "refresh=example; accessToken=outdated").header("Origin", "https://n.novelia.cc")
            .post(ByteArray(0).toRequestBody()).build()
        val prepared = router.prepare(request, sources.capture())
        assertEquals("refresh=example", prepared.header("Cookie"))
        assertEquals("https://book.xkvi.top", prepared.header("Origin"))
        val url = Request.Builder().url("https://n.novelia.cc/api/wenku/test/file/a%20b%2Fc?translations=sakura&translations=gpt&filename=%E4%B9%A6").build()
        assertEquals(url.url.encodedPath, router.prepare(url, sources.capture()).url.encodedPath)
        assertEquals(url.url.encodedQuery, router.prepare(url, sources.capture()).url.encodedQuery)
    }

    @Test fun everyForumRouteUsesMirrorAndReturnsToForumUpstream() {
        val sources = sources().apply { select(BookSource.XKVI) }
        val router = BookSourceInterceptor(sources)
        val paths = listOf(
            "/api/v1/category", "/api/v1/category/", "/api/v1/post", "/api/v1/post/5/",
            "/api/v1/post/5/favorite", "/api/v1/post/5/comment/8/reply",
            "/api/v1/comment/12", "/api/v1/external/comment/novel/web-test-book",
            "/api/v1/me/post", "/api/v1/me/favorite",
        )
        val mirrored = paths.map { path ->
            val request = Request.Builder().url("https://forum.novelia.cc$path?tag=1&tag=2&q=%E4%B9%A6%2F%E5%90%8D")
                .header("Origin", BookSource.ORIGINAL.forumOrigin).header("Authorization", "Bearer test-forum").build()
            router.prepare(request, sources.capture()).also {
                assertEquals("book.xkvi.top", it.url.host)
                assertEquals(request.url.encodedPath, it.url.encodedPath)
                assertEquals(request.url.encodedQuery, it.url.encodedQuery)
                assertEquals("Bearer test-forum", it.header("Authorization"))
                assertEquals("accessToken=test-gateway-only", it.header("Cookie"))
                assertEquals(BookSource.XKVI.forumOrigin, it.header("Origin"))
            }
        }
        sources.select(BookSource.ORIGINAL)
        mirrored.forEach { request ->
            // 新请求引用镜像 URL；旧请求的来源绑定本身不能跨越切换。
            val restored = router.prepare(Request.Builder().url(request.url).header("Cookie", request.header("Cookie")!!).build(), sources.capture())
            assertEquals("forum.novelia.cc", restored.url.host)
            assertEquals(request.url.encodedPath, restored.url.encodedPath)
            assertEquals(request.url.encodedQuery, restored.url.encodedQuery)
            assertNull(restored.header("Cookie"))
        }
        val original = Request.Builder().url("https://forum.novelia.cc/api/v1/post/")
            .header("Origin", BookSource.ORIGINAL.forumOrigin).build()
        assertEquals(original.header("Origin"), router.prepare(original, sources.capture()).header("Origin"))
    }

    @Test fun forumWritesKeepMethodBodyAndForumBearerOnMirror() {
        val sources = sources().apply { select(BookSource.XKVI) }
        val router = BookSourceInterceptor(sources)
        for((method, path) in listOf(
            "POST" to "/api/v1/post/", "PATCH" to "/api/v1/post/5/", "DELETE" to "/api/v1/post/5/",
            "POST" to "/api/v1/post/5/comment", "PATCH" to "/api/v1/comment/12",
            "DELETE" to "/api/v1/comment/12", "PUT" to "/api/v1/post/5/favorite",
        )) {
            val request = Request.Builder().url("https://forum.novelia.cc$path")
                .method(method, "test-json".toRequestBody()).header("Authorization", "Bearer test-forum").build()
            val prepared = router.prepare(request, sources.capture())
            assertEquals("book.xkvi.top", prepared.url.host)
            assertEquals(method, prepared.method)
            assertSame(request.body, prepared.body)
            assertEquals("Bearer test-forum", prepared.header("Authorization"))
            assertEquals("accessToken=test-gateway-only", prepared.header("Cookie"))
        }
    }

    @Test fun onlyAuthPostsAreExemptFromGatewayCookie() {
        val sources = sources().apply { select(BookSource.XKVI) }
        val router = BookSourceInterceptor(sources)
        for(method in listOf("POST", "GET", "HEAD", "OPTIONS")) {
            val request = Request.Builder().url("https://auth.novelia.cc/api/v1/auth/refresh?app=f")
                .header("Cookie", "refresh=test-refresh; accessToken=outdated").header("Origin", BookSource.ORIGINAL.forumOrigin)
                .method(method, if(method == "POST") ByteArray(0).toRequestBody() else null).build()
            val prepared = router.prepare(request, sources.capture())
            assertEquals("book.xkvi.top", prepared.url.host)
            assertEquals("f", prepared.url.queryParameter("app"))
            assertEquals(if(method == "POST") "refresh=test-refresh" else "refresh=test-refresh; accessToken=test-gateway-only", prepared.header("Cookie"))
            assertEquals(BookSource.XKVI.origin, prepared.header("Origin"))
        }
    }

    @Test fun unsupportedAuthAndForumPathsKeepOriginalUpstreams() {
        val sources = sources().apply { select(BookSource.XKVI) }
        val router = BookSourceInterceptor(sources)
        for(url in listOf(
            "https://auth.novelia.cc/api/v1/me/strikes", "https://auth.novelia.cc/api/v1/me/attention-status",
            "https://auth.novelia.cc/api/v1/me/strikes/read-state", "https://auth.novelia.cc/",
            "https://forum.novelia.cc/", "https://forum.novelia.cc/api/v1/other", "https://forum.novelia.cc/cdn-cgi/trace",
        )) {
            val request = Request.Builder().url(url).build()
            val prepared = router.prepare(request, sources.capture())
            assertEquals(request.url, prepared.url)
            assertNull(prepared.header("Cookie"))
        }
    }

    @Test fun remainingApiAndDownloadPathsReturnToBookUpstreamWithSegmentBoundaries() {
        val sources = sources()
        val router = BookSourceInterceptor(sources)
        for(path in listOf(
            "/api/novel", "/api/comment", "/api/v1/posts", "/api/v1/category-other", "/api/v1/commentary",
            "/api/v1/me/posts", "/api/v1/me/favorite-extra", "/api/v1/external/comments", "/api/v1/auth-extra",
            "/files-temp", "/files-temp/sample.txt", "/files-temp/sample.epub",
        )) {
            val request = Request.Builder().url("https://book.xkvi.top$path?filename=%E4%B9%A6").build()
            val prepared = router.prepare(request, sources.capture())
            assertEquals("n.novelia.cc", prepared.url.host)
            assertEquals(request.url.encodedPath, prepared.url.encodedPath)
            assertEquals(request.url.encodedQuery, prepared.url.encodedQuery)
        }
    }

    @Test fun mirroredAuthPathsReturnToAuthUpstream() {
        val sources = sources()
        val router = BookSourceInterceptor(sources)
        for(path in listOf("/api/v1/auth/login", "/api/v1/auth/register", "/api/v1/auth/otp/request", "/api/v1/auth/refresh?app=f")) {
            val request = Request.Builder().url("https://book.xkvi.top$path").post(ByteArray(0).toRequestBody()).build()
            val prepared = router.prepare(request, sources.capture())
            assertEquals("auth.novelia.cc", prepared.url.host)
            assertEquals(request.url.encodedPath, prepared.url.encodedPath)
            assertEquals(request.url.encodedQuery, prepared.url.encodedQuery)
            assertNull(prepared.header("Cookie"))
        }
    }

    @Test fun gatewayCookieNeverGoesToOriginalForeignHostsHttpOrUntrustedPorts() {
        val sources = sources()
        val router = BookSourceInterceptor(sources)
        for(source in BookSource.entries) {
            sources.select(source)
            for(url in listOf("https://cdn.example/file", "https://book.xkvi.top.example/", "http://book.xkvi.top/", "https://book.xkvi.top:8443/", "https://name@book.xkvi.top/")) {
                val request = Request.Builder().url(url).header("Cookie", "accessToken=stale").build()
                assertNull(router.prepare(request, sources.capture()).header("Cookie"))
            }
        }
        sources.select(BookSource.ORIGINAL)
        val request = router.prepare(Request.Builder().url("https://book.xkvi.top/api/novel").build(), sources.capture())
        assertEquals("n.novelia.cc", request.url.host)
        assertNull(request.header("Cookie"))
    }

    @Test fun switchingBackInvalidatesOldRequestsWithoutSharingCacheIdentity() {
        val sources = sources()
        val old = sources.capture()
        sources.select(BookSource.XKVI)
        sources.select(BookSource.ORIGINAL)
        assertThrows(SessionChangedException::class.java) { sources.withSelection(old) { fail() } }
        assertNotEquals(SessionBinding("alice", 1).cacheAccount, SessionBinding("alice", 1, "xkvi").cacheAccount)
        assertNotEquals(SessionBinding(null, 1).cacheAccount, SessionBinding(null, 1, "xkvi").cacheAccount)
    }

    @Test fun failedSaveDoesNotPublishSourceChange() {
        val sources = BookSources(initialToken = "test-gateway-only", persist = { error("disk unavailable") })
        val before = sources.capture()
        assertThrows(IllegalStateException::class.java) { sources.select(BookSource.XKVI) }
        assertEquals(before, sources.capture())
    }

    @Test fun missingBuildCredentialCannotSelectMirror() {
        val sources = BookSources()
        assertThrows(IllegalArgumentException::class.java) { sources.select(BookSource.XKVI) }
        assertEquals(BookSource.ORIGINAL, sources.capture().source)
    }

    @Test fun originalAuthKeepsItsOwnCookiesEvenWhenNamesMatchGatewayCookie() {
        val sources = sources()
        val request = Request.Builder().url("https://auth.novelia.cc/api/v1/auth/refresh?app=n")
            .header("Cookie", "accessToken=test-original-session; refresh=test-refresh").build()
        val prepared = BookSourceInterceptor(sources).prepare(request, sources.capture())
        assertEquals(request.header("Cookie"), prepared.header("Cookie"))
        assertEquals(request.url, prepared.url)
    }

    @Test fun mirroredBearerChallengeRestoresUnauthorizedButOrdinaryBadGatewayDoesNot() {
        val sources = sources().apply { select(BookSource.XKVI) }
        for(challenge in listOf("Bearer realm=\"Access token\"", "Basic", "")) {
            val client = OkHttpClient.Builder().addInterceptor(BookSourceInterceptor(sources)).addInterceptor { chain ->
                response(chain.request(), 502).newBuilder().header("WWW-Authenticate", challenge).build()
            }.build()
            client.newCall(Request.Builder().url("https://n.novelia.cc/api/user/favored").build()).execute().use {
                assertEquals(if(challenge.startsWith("Bearer")) 401 else 502, it.code)
                assertEquals("ok", it.body!!.string())
            }
        }
    }

    @Test fun redirectDropsAccountAndGatewayCredentialsOnOtherOrigins() {
        val sources = sources().apply { select(BookSource.XKVI) }
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor(BookSourceInterceptor(sources)).addInterceptor { chain ->
            requests += chain.request()
            when(requests.size) {
                1 -> response(chain.request(), 302, "/file/ready")
                2 -> response(chain.request(), 302, "https://cdn.example/book.epub")
                else -> response(chain.request())
            }
        }.echRedirects(true).build()
        assertFalse(client.followRedirects)
        client.newCall(Request.Builder().url("https://n.novelia.cc/api/novel/book/file").header("Authorization", "Bearer test-account").build()).execute().use {
            assertEquals("ok", it.body!!.string())
        }
        assertEquals(3, requests.size)
        assertEquals("accessToken=test-gateway-only", requests[1].header("Cookie"))
        assertEquals("Bearer test-account", requests[1].header("Authorization"))
        assertNull(requests[2].header("Cookie")); assertNull(requests[2].header("Authorization"))
    }

    @Test fun mirrorDoesNotUseEchAndOriginalStillDoes() {
        val sources = sources()
        var nativeCalls = 0
        var directCalls = 0
        val engine = object : EchEngine {
            override fun open(request: Request, timeouts: EchTimeouts): EchExchange {
                nativeCalls++
                assertEquals("n.novelia.cc", request.url.host)
                assertNull(request.header("Cookie"))
                return object : EchExchange {
                    override fun execute() = EchReply(200, Protocol.HTTP_2, Headers.Builder().build(), 0)
                    override fun read(maxBytes: Long) = ByteArray(0)
                    override fun cancel() {}
                }
            }
        }
        val client = OkHttpClient.Builder().addInterceptor(BookSourceInterceptor(sources)).addInterceptor(EchInterceptor(engine, { true }))
            .addInterceptor { chain -> directCalls++; assertEquals("book.xkvi.top", chain.request().url.host); response(chain.request()) }
            .followRedirects(false).build()
        client.newCall(Request.Builder().url("https://n.novelia.cc/api/novel").build()).execute().close()
        sources.select(BookSource.XKVI)
        client.newCall(Request.Builder().url("https://n.novelia.cc/api/novel").build()).execute().close()
        assertEquals(1, nativeCalls); assertEquals(1, directCalls)
    }

    @Test fun switchingDuringResponseRejectsItAndDuringStreamStopsReading() {
        val sources = sources().apply { select(BookSource.XKVI) }
        val client = OkHttpClient.Builder().addInterceptor(BookSourceInterceptor(sources)).addInterceptor { chain ->
            sources.select(BookSource.ORIGINAL)
            response(chain.request())
        }.build()
        assertThrows(SessionChangedException::class.java) { client.newCall(Request.Builder().url("https://n.novelia.cc/api/novel").build()).execute() }
        sources.select(BookSource.XKVI)
        val streaming = OkHttpClient.Builder().addInterceptor(BookSourceInterceptor(sources)).addInterceptor { response(it.request()) }.build()
        streaming.newCall(Request.Builder().url("https://n.novelia.cc/cover").build()).execute().use {
            sources.select(BookSource.ORIGINAL)
            assertThrows(SessionChangedException::class.java) { it.body!!.string() }
        }
    }

    @Test fun requestCapturedBeforeSwitchCannotStartOnNewSource() {
        val sources = sources()
        val request = Request.Builder().url("https://n.novelia.cc/api/novel").tag(SourceSelection::class.java, sources.capture()).build()
        sources.select(BookSource.XKVI)
        val client = OkHttpClient.Builder().addInterceptor(BookSourceInterceptor(sources)).addInterceptor { error("must not send") }.build()
        assertThrows(SessionChangedException::class.java) { client.newCall(request).execute() }
    }
}
