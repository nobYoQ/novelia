@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cc.novelia.app.ui.account

import android.annotation.SuppressLint
import android.webkit.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import cc.novelia.app.data.sync.CloudSyncWorker
import cc.novelia.app.ui.components.Screen
import cc.novelia.app.ui.components.friendlyMessage
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.navigation.finishLoginNavigation
import cc.novelia.app.ui.theme.appReducedMotion
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
@Composable fun LoginScreen(c: AppController) {
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }; var loading by remember { mutableStateOf(true) }; val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun complete() {
        if(busy) return
        busy = true; scope.launch {
            try { if(c.session.refresh()) { if(c.store.state.value.autoSync) c.session.profile.value?.username?.let { CloudSyncWorker.enqueue(c.app, it) }; finishLoginNavigation(c); c.message("已登录") } else error = "尚未取得登录会话。请在下方完成登录，再点「完成登录」。" }
            catch(e: Exception) { error = e.friendlyMessage() } finally { busy = false }
        }
    }
    val web = remember { WebView(context).apply {
        layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = true; settings.domStorageEnabled = true; settings.allowFileAccess = false; settings.allowContentAccess = false; settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) { loading = false }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, e: WebResourceError?) { if(request?.isForMainFrame == true) { error = "认证页面加载失败，请检查网络后重试"; loading = false } }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return true
                return if(uri.scheme == "https" && (!request.isForMainFrame || uri.host in setOf("auth.novelia.cc", "n.novelia.cc"))) false else { if(request.isForMainFrame && uri.scheme == "https") c.external(uri.toString()); true }
            }
        }
        if(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.addWebMessageListener(this, "NoveliaAuth", setOf("https://n.novelia.cc")) { _, message, origin, mainFrame, _ ->
            if(mainFrame && origin.scheme == "https" && origin.host == "n.novelia.cc" && message.data == "login_success") post { complete() }
        }
        loadDataWithBaseURL("https://n.novelia.cc", """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>html,body{margin:0;background:#f7faf5}iframe{position:fixed;inset:0;width:100vw;height:100vh;border:0}</style></head><body><iframe title="Novelia 统一认证" src="https://auth.novelia.cc/?app=n&amp;theme=system"></iframe><script>window.addEventListener('message',function(e){if(e.origin==='https://auth.novelia.cc'&&e.data&&e.data.type==='login_success'&&window.NoveliaAuth){window.NoveliaAuth.postMessage('login_success');}});</script></body></html>""", "text/html", "UTF-8", null)
    } }
    DisposableEffect(web) { onDispose { web.stopLoading(); web.destroy(); c.afterLogin = null } }
    Screen("登录 Novelia", c::back, actions = { TextButton(onClick = ::complete, enabled = !busy) { Text(if(busy) "验证中…" else "完成登录") } }) { padding -> Column(Modifier.padding(padding)) {
        Text("使用原站统一账号登录、注册或找回密码。密码由认证网站直接处理。", Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if(loading || busy) { if(appReducedMotion()) Text(if(busy) "验证中…" else "正在加载认证页面…", Modifier.padding(horizontal = 20.dp)) else LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium); TextButton(onClick = { error = null; loading = true; web.reload() }) { Text("重新加载认证页") } }
        AndroidView(factory = { web }, modifier = Modifier.weight(1f).fillMaxWidth())
    } }
}
