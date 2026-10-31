package cc.novelia.app.ui.account

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import cc.novelia.app.data.auth.AuthTarget
import cc.novelia.app.data.auth.verificationPageUrl
import cc.novelia.app.data.auth.verificationSnapshotScript
import cc.novelia.app.ui.components.base.AppTextButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import kotlin.coroutines.resume

@Composable internal fun TurnstileDialog(
    client: OkHttpClient,
    target: AuthTarget,
    resetting: Boolean,
    onDismiss: () -> Unit,
    onVerified: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val pageUrl = remember(target, resetting, dark) { verificationPageUrl(target, resetting, dark) }
    var attempt by remember { mutableIntStateOf(0) }
    var ready by remember(attempt) { mutableStateOf(false) }
    var failure by remember(attempt) { mutableStateOf(false) }
    var expired by remember(attempt) { mutableStateOf(false) }
    val verified by rememberUpdatedState(onVerified)
    val web = remember(pageUrl, attempt) { TurnstileBrowser(context, client, pageUrl) { failure = true } }
    DisposableEffect(web) { onDispose { web.release() } }
    SideEffect {
        web.isEnabled = ready && !failure
        web.importantForAccessibility = if(ready && !failure) android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            else android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    LaunchedEffect(web, failure) {
        if(failure) return@LaunchedEffect
        val started = SystemClock.elapsedRealtime()
        while(true) {
            delay(500)
            if(!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) continue
            if(web.url == pageUrl) {
                val snapshot = suspendCancellableCoroutine<String?> { continuation ->
                    web.evaluateJavascript(verificationSnapshotScript) { result ->
                        if(continuation.isActive) continuation.resume(result)
                    }
                }
                val result = runCatching { Json.parseToJsonElement(snapshot ?: "null").jsonObject }.getOrNull()
                if(result?.get("failed")?.jsonPrimitive?.contentOrNull == "true") { failure = true; break }
                if(result?.get("expired")?.jsonPrimitive?.contentOrNull == "true") { expired = true; failure = true; break }
                ready = result?.get("ready")?.jsonPrimitive?.contentOrNull == "true"
                val token = result?.get("token")?.jsonPrimitive?.contentOrNull.orEmpty()
                if(token.isNotBlank() && token.length <= 2048) {
                    verified(token)
                    return@LaunchedEffect
                }
            }
            if(!ready && SystemClock.elapsedRealtime() - started > 45_000) { failure = true; break }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(horizontal = 8.dp).widthIn(max = 440.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.large) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp).testTag("login-verification")) {
                Text("人机验证", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleLarge)
                Text("完成验证后将自动发送邮箱验证码", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                    key(web) {
                        AndroidView(factory = { web }, modifier = Modifier.fillMaxSize().alpha(if(ready && !failure) 1f else 0f))
                    }
                    if(failure) Text(if(expired) "人机验证已过期，请重新加载" else "人机验证加载失败，请重试",
                        Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                    else if(!ready) CircularProgressIndicator(Modifier.size(28.dp))
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                    AppTextButton(onClick = { attempt++ }) { Text("重新加载") }
                    AppTextButton(onClick = onDismiss) { Text("取消") }
                }
            }
        }
    }
}
