package cc.novelia.app.ui.navigation

import android.content.ClipboardManager
import android.view.ViewTreeObserver
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.catalog.ClipboardSiteLink
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** 每次返回前台且窗口获得焦点后读一次；后台不监听、不轮询，关闭弹窗也不会重复读取。 */
@Composable internal fun ObserveClipboardLinks(c: AppController, externalLinkPending: Boolean) {
    val enabled by remember(c.store) { c.store.state.map { it.clipboardLinkHints }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = false)
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val incoming by rememberUpdatedState(externalLinkPending)
    var candidate by remember { mutableStateOf<ClipboardSiteLink?>(null) }
    DisposableEffect(enabled, view, lifecycle) {
        var pending = enabled && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val read = Runnable {
            if(pending && view.hasWindowFocus() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                pending = false
                if(!incoming) {
                    val text = runCatching {
                        val manager = view.context.getSystemService(ClipboardManager::class.java)
                        val clip = manager?.primaryClip
                        if(clip != null && clip.itemCount > 0) clip.getItemAt(0).let { it.text?.take(16_384)?.toString() ?: it.uri?.toString() } else null
                    }.getOrNull()
                    candidate = c.app.clipboardLinkHistory.next(text)
                }
            }
        }
        val focus = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if(focused && pending) view.post(read) }
        val observer = LifecycleEventObserver { _, event ->
            when(event) {
                Lifecycle.Event.ON_RESUME -> { pending = enabled; view.post(read) }
                Lifecycle.Event.ON_PAUSE -> { pending = false; candidate = null; view.removeCallbacks(read) }
                else -> Unit
            }
        }
        if(enabled) {
            lifecycle.addObserver(observer)
            view.viewTreeObserver.addOnWindowFocusChangeListener(focus)
            view.post(read)
        }
        onDispose {
            pending = false
            view.removeCallbacks(read)
            lifecycle.removeObserver(observer)
            if(view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(focus)
        }
    }
    LaunchedEffect(enabled, candidate) {
        if(!enabled) { candidate = null; return@LaunchedEffect }
        val link = candidate ?: return@LaunchedEffect
        val result = c.snackbar.showSnackbar("剪贴板中发现原站${link.label}链接", actionLabel = "打开",
            withDismissAction = true, duration = SnackbarDuration.Long)
        candidate = null
        if(result == SnackbarResult.ActionPerformed) c.go(link.route)
    }
}
