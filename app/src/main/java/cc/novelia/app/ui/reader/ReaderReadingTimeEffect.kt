package cc.novelia.app.ui.reader

import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cc.novelia.app.data.model.withReadingTime
import cc.novelia.app.reader.ReadingSessionClock
import cc.novelia.app.ui.navigation.AppController
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** 仅在当前阅读页面前台计时。退出、锁屏与面板变化立即结算；平时每 15 秒保存。 */
@Composable internal fun rememberReaderReadingTime(
    c: AppController, bookKey: String, title: String, revision: Long, eligible: Boolean, automatic: Boolean,
): () -> Unit {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by lifecycle.currentStateFlow.collectAsState()
    val clock = remember(c, bookKey, revision) { ReadingSessionClock() }
    val currentEligible by rememberUpdatedState(eligible)
    val currentAutomatic by rememberUpdatedState(automatic)
    val currentTitle by rememberUpdatedState(title)
    fun sample(active: Boolean, interacted: Boolean = false) {
        clock.update(SystemClock.elapsedRealtime(), System.currentTimeMillis(), active, currentAutomatic, interacted)?.let { slice ->
            c.store.update { it.withReadingTime(bookKey, currentTitle, slice.startedAt, slice.millis, revision) }
        }
    }
    DisposableEffect(clock, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if(event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) sample(false)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); sample(false) }
    }
    LaunchedEffect(clock, eligible, automatic, state) {
        val active = eligible && state.isAtLeast(Lifecycle.State.RESUMED)
        sample(active)
        if(active) while(isActive) { delay(15_000); sample(currentEligible && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    }
    return { sample(currentEligible && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED), interacted = true) }
}
