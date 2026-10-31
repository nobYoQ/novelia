package cc.novelia.app.ui.account

import android.net.Uri
import android.os.SystemClock
import android.webkit.WebResourceRequest
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.auth.AuthTarget
import cc.novelia.app.data.auth.verificationPageUrl
import cc.novelia.app.data.auth.verificationSnapshotScript
import cc.novelia.app.data.network.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

/** Synthetic host page only: no real challenge, account credentials, or mail requests. */
class TurnstileBrowserTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun evaluate(web: TurnstileBrowser, script: String): String {
        val done = CountDownLatch(1)
        var value = ""
        instrumentation.runOnMainSync {
            web.evaluateJavascript(script) { value = it; done.countDown() }
        }
        assertTrue("JavaScript evaluation should finish", done.await(10, TimeUnit.SECONDS))
        return value
    }

    private fun awaitValue(web: TurnstileBrowser, script: String, expected: String) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while(SystemClock.elapsedRealtime() < deadline) {
            if(evaluate(web, script) == expected) return
            SystemClock.sleep(100)
        }
        assertEquals(expected, evaluate(web, script))
    }

    @Test fun realHostPageUsesEchAndWebViewCannotSubmitAuthRequests() {
        val requests = CopyOnWriteArrayList<Request>()
        val failures = CopyOnWriteArrayList<Boolean>()
        val client = OkHttpClient.Builder().addInterceptor(EchInterceptor(object : EchEngine {
            override fun open(request: Request, timeouts: EchTimeouts): EchExchange {
                requests += request
                assertEquals("auth.novelia.cc", request.url.host)
                assertEquals("GET", request.method)
                assertNull(request.header("Cookie"))
                assertNull(request.header("Authorization"))
                val asset = request.url.encodedPath.endsWith(".js")
                val content = if(asset) "window.turnstile = { getResponse: () => 'synthetic-verification', isExpired: () => false };"
                    else """<!doctype html><html><head><script src="/assets/synthetic.js"></script></head>
                        <body><main><h1>Duplicate form</h1><form><input id="account"><div aria-busy="false">
                        <div class="leading-0">Synthetic widget</div></div></form></main></body></html>"""
                val body = Buffer().writeUtf8(content)
                return object : EchExchange {
                    override fun execute() = EchReply(200, Protocol.HTTP_2,
                        Headers.headersOf("Content-Type", if(asset) "application/javascript" else "text/html"), -1)
                    override fun read(maxBytes: Long) = body.readByteArray(minOf(maxBytes, body.size))
                    override fun cancel() { }
                }
            }
        }, { true })).addInterceptor { error("Verification page must not fall back to a direct connection") }.build()
        lateinit var web: TurnstileBrowser
        instrumentation.runOnMainSync {
            web = TurnstileBrowser(instrumentation.targetContext, client,
                verificationPageUrl(AuthTarget.NOVEL, resetting = false, dark = false)) { failures += true }
        }
        try {
            awaitValue(web, "typeof window.turnstile", "\"object\"")
            val result = evaluate(web, verificationSnapshotScript)
            assertTrue(result.contains("\"ready\":true"))
            assertTrue(result.contains("\"token\":\"synthetic-verification\""))
            evaluate(web, "window.turnstile.isExpired = () => true; void 0;")
            val expired = evaluate(web, verificationSnapshotScript)
            assertTrue(expired.contains("\"expired\":true"))
            assertFalse(expired.contains("synthetic-verification"))
            assertEquals("\"none\"", evaluate(web, "getComputedStyle(document.getElementById('account')).display"))
            assertEquals(setOf("/register", "/assets/synthetic.js"), requests.map { it.url.encodedPath }.toSet())

            evaluate(web, """fetch('/api/v1/auth/otp/request', {method:'POST', body:'synthetic'})
                .then(r => window.syntheticPostStatus = r.status).catch(() => window.syntheticPostStatus = -1); void 0;""")
            awaitValue(web, "window.syntheticPostStatus", "403")
            assertFalse(requests.any { it.url.encodedPath.startsWith("/api/") })
            assertTrue(failures.isEmpty())

            instrumentation.runOnMainSync {
                val challenge = request("https://challenges.cloudflare.com/turnstile/v0/api.js", "POST")
                assertNull(web.webViewClient.shouldInterceptRequest(web, challenge))
                val forbidden = request("https://auth.novelia.cc/api/v1/auth/register", "POST")
                assertEquals(403, web.webViewClient.shouldInterceptRequest(web, forbidden)?.statusCode)
            }
        } finally { instrumentation.runOnMainSync { web.release() } }
    }

    private fun request(url: String, verb: String) = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(url)
        override fun isForMainFrame() = false
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = verb
        override fun getRequestHeaders() = emptyMap<String, String>()
    }
}
