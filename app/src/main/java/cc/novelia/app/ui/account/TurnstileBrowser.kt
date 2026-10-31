package cc.novelia.app.ui.account

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import cc.novelia.app.data.auth.VerificationRoute
import cc.novelia.app.data.auth.verificationRoute
import cc.novelia.app.data.network.newNetworkCall
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request

/** No account credentials or JS-to-native interface are exposed to the verification page. */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
internal class TurnstileBrowser(
    context: Context,
    client: OkHttpClient,
    val pageUrl: String,
    private val failed: () -> Unit,
) : WebView(context) {
    private val closed = AtomicBoolean()
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val pages = client.newBuilder().cookieJar(CookieJar.NO_COOKIES).cache(null)
        .followRedirects(false).followSslRedirects(false).build()

    init {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?) = true
        }
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                when(verificationRoute(request.url.toString(), request.method, request.isForMainFrame, pageUrl)) {
                    VerificationRoute.CLOUDFLARE -> null
                    VerificationRoute.BLOCK -> blocked()
                    VerificationRoute.APP -> fetchPage(request)
                }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if(!request.isForMainFrame && request.url.toString() in setOf("about:blank", "about:srcdoc")) return false
                return verificationRoute(request.url.toString(), request.method, request.isForMainFrame, pageUrl) == VerificationRoute.BLOCK
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if(verificationRoute(request.url.toString(), request.method, request.isForMainFrame, pageUrl) != VerificationRoute.BLOCK)
                    reportFailure()
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if(request.isForMainFrame) reportFailure()
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                reportFailure()
            }
        }
        loadUrl(pageUrl)
    }

    private fun fetchPage(request: WebResourceRequest): WebResourceResponse {
        if(closed.get()) return blocked()
        val builder = Request.Builder().url(request.url.toString()).header("Cache-Control", "no-cache")
        // In particular, do not forward WebView cookies or authorization to the auth service.
        request.requestHeaders.filterKeys { it.lowercase() in setOf("accept", "accept-language", "user-agent") }
            .forEach { (name, value) -> builder.header(name, value) }
        val call = pages.newNetworkCall(builder.build())
        calls.add(call)
        if(closed.get()) call.cancel()
        return try {
            call.execute().use { response ->
                if(!response.isSuccessful) throw IOException("Verification page unavailable")
                // Public HTML/assets only. Bound memory, close the upstream response on every path.
                val bytes = response.peekBody(MAX_RESOURCE_BYTES + 1).bytes()
                if(bytes.size > MAX_RESOURCE_BYTES) throw IOException("Verification page too large")
                val media = response.body?.contentType()
                val headers = response.headers.toMultimap().filterKeys {
                    it.lowercase() !in setOf("set-cookie", "content-length", "content-encoding", "location", "cache-control")
                }.mapValues { it.value.joinToString(", ") }.toMutableMap()
                headers["Cache-Control"] = "no-store"
                WebResourceResponse(media?.let { "${it.type}/${it.subtype}" } ?: "text/html",
                    media?.charset()?.name() ?: "UTF-8", 200, "OK", headers, ByteArrayInputStream(bytes))
            }
        } catch(_: Exception) {
            reportFailure()
            WebResourceResponse("text/plain", "UTF-8", 503, "Verification unavailable", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        } finally { calls.remove(call) }
    }

    private fun reportFailure() { post { if(!closed.get()) failed() } }

    fun release() {
        if(!closed.compareAndSet(false, true)) return
        calls.forEach(Call::cancel)
        calls.clear()
        stopLoading()
        destroy()
    }

    private fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked",
        emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private companion object { const val MAX_RESOURCE_BYTES = 2 * 1024 * 1024L }
}
