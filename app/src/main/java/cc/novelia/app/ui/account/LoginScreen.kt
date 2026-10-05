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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
@Composable fun LoginScreen(c: AppController, forum: Boolean = false) {
    if(!forum && c.app.bookSources.capture().source == cc.novelia.app.data.network.BookSource.XKVI) {
        MirrorLoginScreen(c)
        return
    }
    if(forum) ForumLoginScreen(c) else WebLoginScreen(c)
}

@Composable private fun ForumLoginScreen(c: AppController) {
    var checking by remember { mutableStateOf(true) }
    DisposableEffect(c) { onDispose { c.afterLogin = null } }
    LaunchedEffect(c.forumSession) {
        val signedIn = try { c.forumSession.loginFromSharedAuth(c.session, explicit = true) }
            catch(error: CancellationException) { throw error }
            catch(_: Exception) { false }
        // 社区自动登录可能已完成；复用其会话，避免再次显示认证页。
        if(signedIn || c.forumSession.profile.value != null) finishLoginNavigation(c, forum = true) else checking = false
    }
    if(checking) Screen("登录 Novelia 论坛", c::back) { padding ->
        Column(Modifier.padding(padding).padding(20.dp)) {
            Text("正在恢复登录状态…")
            if(!appReducedMotion()) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
        }
    } else if(c.app.bookSources.capture().source == cc.novelia.app.data.network.BookSource.XKVI)
        MirrorLoginScreen(c, forum = true)
    else WebLoginScreen(c, forum = true)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable private fun WebLoginScreen(c: AppController, forum: Boolean = false) {
    val loginSession = if(forum) c.forumSession else c.session
    val target = loginSession.target
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }; var loading by remember { mutableStateOf(true) }; val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun complete() {
        if(busy) return
        busy = true; scope.launch {
            try { if(loginSession.refresh()) {
                if(!forum && c.store.state.value.autoSync) loginSession.profile.value?.username?.let { CloudSyncWorker.enqueue(c.app, it) }
                finishLoginNavigation(c, forum)
                c.message("已登录")
            } else error = "尚未取得登录会话。请在下方完成登录，再点「完成登录」。" }
            catch(e: Exception) { error = e.friendlyMessage() } finally { busy = false }
        }
    }
    val web = remember(target) { WebView(context).apply {
        layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = true; settings.domStorageEnabled = true; settings.allowFileAccess = false; settings.allowContentAccess = false; settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) { loading = false }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, e: WebResourceError?) { if(request?.isForMainFrame == true) { error = "认证页面加载失败，请检查网络后重试"; loading = false } }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return true
                return if(uri.scheme == "https" && (!request.isForMainFrame || uri.host in setOf("auth.novelia.cc", android.net.Uri.parse(target.origin).host))) false else { if(request.isForMainFrame && uri.scheme == "https") c.external(uri.toString()); true }
            }
        }
        if(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.addWebMessageListener(this, "NoveliaAuth", setOf(target.origin)) { _, message, origin, mainFrame, _ ->
            if(mainFrame && origin.scheme == "https" && origin.host == android.net.Uri.parse(target.origin).host && message.data == "login_success") post { complete() }
        }
        loadDataWithBaseURL(target.origin, """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>html,body{margin:0;background:#f7faf5}iframe{position:fixed;inset:0;width:100vw;height:100vh;border:0}</style></head><body><iframe title="Novelia 统一认证" src="https://auth.novelia.cc/?app=${target.appId}&amp;theme=system"></iframe><script>window.addEventListener('message',function(e){if(e.origin==='https://auth.novelia.cc'&&e.data&&e.data.type==='login_success'&&window.NoveliaAuth){window.NoveliaAuth.postMessage('login_success');}});</script></body></html>""", "text/html", "UTF-8", null)
    } }
    DisposableEffect(web) { onDispose { web.stopLoading(); web.destroy(); c.afterLogin = null } }
    Screen(if(forum) "登录 Novelia 论坛" else "登录 Novelia", c::back, actions = { TextButton(onClick = ::complete, enabled = !busy) { Text(if(busy) "验证中…" else "完成登录") } }) { padding -> Column(Modifier.padding(padding)) {
        Text("使用Novelia账号登录、注册或找回密码。密码由认证网站直接处理。", Modifier.padding(horizontal = 20.dp, vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if(loading || busy) { if(appReducedMotion()) Text(if(busy) "验证中…" else "正在加载认证页面…", Modifier.padding(horizontal = 20.dp)) else LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium); TextButton(onClick = { error = null; loading = true; web.reload() }) { Text("重新加载认证页") } }
        AndroidView(factory = { web }, modifier = Modifier.weight(1f).fillMaxWidth())
    } }
}
