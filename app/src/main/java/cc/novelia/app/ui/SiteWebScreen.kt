package cc.novelia.app.ui

import android.annotation.SuppressLint
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cc.novelia.app.data.MarkdownLinks

/** Original-site pages without a native screen remain inside the app. No JavaScript bridge. */
@SuppressLint("SetJavaScriptEnabled")
@Composable internal fun SiteWebScreen(c: AppController, destination: String) {
    val context = LocalContext.current
    var loading by remember(destination) { mutableStateOf(true) }
    var failed by remember(destination) { mutableStateOf(false) }
    val eInk = LocalEInkMode.current
    var canGoBack by remember(destination) { mutableStateOf(false) }
    var canGoForward by remember(destination) { mutableStateOf(false) }
    val web = remember(destination) { PagedSiteWebView(context).apply {
        onPageAvailabilityChanged = { back, forward -> canGoBack = back; canGoForward = forward }
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                loading = true; failed = false
            }
            override fun onPageFinished(view: WebView, url: String?) {
                loading = false
                applyMotionPreference()
                canGoBack = canScrollVertically(-1); canGoForward = canScrollVertically(1)
                if (url != null && MarkdownLinks.isInternal(url) && !android.net.Uri.parse(url).fragment.isNullOrEmpty()) {
                    // The SPA fetches article content after the document load. Its router
                    // may try to find the heading before it exists on a cold navigation.
                    view.evaluateJavascript(SITE_ANCHOR_SCRIPT, null)
                }
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) { loading = false; failed = true }
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val url = MarkdownLinks.resolve(request.url.toString()) ?: return true
                if (MarkdownLinks.isInternal(url) && MarkdownLinks.nativeRoute(url) == null) return false
                c.openMarkdownLink(url)
                return true
            }
        }
        setOnScrollChangeListener { _, _, _, _, _ -> canGoBack = canScrollVertically(-1); canGoForward = canScrollVertically(1) }
        MarkdownLinks.resolve(destination)?.takeIf(MarkdownLinks::isInternal)?.let(::loadUrl)
            ?: run { loading = false; failed = true }
    } }
    SideEffect { web.eInkMode = eInk }
    fun back() { if (web.canGoBack()) web.goBack() else c.back() }
    // SPA pushState/hash changes do not always trigger onPageFinished; inspect
    // WebView's current history for both the system and toolbar back actions.
    BackHandler { back() }
    DisposableEffect(web) { onDispose { web.stopLoading(); web.destroy() } }
    Screen("原站页面", ::back) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (loading) { if(eInk) Text("正在加载…", Modifier.padding(horizontal = 16.dp)) else LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (failed) Row(Modifier.padding(16.dp)) {
                Text("页面加载失败，请检查网络。", Modifier.weight(1f))
                TextButton(onClick = { web.reload() }) { Text("重试") }
            }
            AndroidView(factory = { web }, modifier = Modifier.weight(1f).fillMaxWidth())
            if(eInk) ScreenPageButtons(canGoBack, canGoForward, web::page)
        }
    }
}

/** Only uses the current page's fragment; no URL/text interpolation or native JS bridge. */
private val SITE_ANCHOR_SCRIPT = """
    (() => {
        if (!location.hash) return;
        const page = location.href;
        const raw = location.hash.slice(1);
        let decoded = raw;
        try { decoded = decodeURIComponent(raw); } catch (_) {}
        const ids = [raw, decoded, encodeURIComponent(decoded)];
        let observer = null;
        let expiry = 0;
        const events = ['pointerdown', 'touchstart', 'wheel', 'keydown'];
        function cancel() {
            if (observer) observer.disconnect();
            clearTimeout(expiry);
            events.forEach(event => window.removeEventListener(event, cancel, true));
        }
        function locate() {
            if (location.href !== page) { cancel(); return true; }
            const target = ids.map(id => document.getElementById(id)).find(Boolean);
            if (!target || !target.getClientRects().length) return false;
            cancel();
            requestAnimationFrame(() => {
                if (location.href === page) {
                    window.scrollTo({ top: Math.max(0, target.getBoundingClientRect().top + window.scrollY - 58), behavior: 'auto' });
                }
            });
            return true;
        }
        if (!locate()) {
            observer = new MutationObserver(locate);
            observer.observe(document.documentElement, { childList: true, subtree: true });
            expiry = setTimeout(cancel, 15000);
            events.forEach(event => window.addEventListener(event, cancel, { capture: true, passive: true }));
        }
    })();
""".trimIndent()
