package cc.novelia.app.ui.components.base

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.network.isRecoverableNetworkFailure
import kotlinx.coroutines.flow.first

/** One retry per failed visible load and later network generation. Leaving the page cancels the observer. */
@Composable internal fun NetworkRecoveryEffect(error: Throwable?, onRetry: () -> Unit) {
    val app = LocalContext.current.applicationContext as? NoveliaApplication ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val retry by rememberUpdatedState(onRetry)
    val failedState = remember(error) { app.ech.networkStatus.value }
    LaunchedEffect(error, lifecycle) {
        if (!error.isRecoverableNetworkFailure()) return@LaunchedEffect
        var retried = false
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (!retried) {
                app.ech.networkStatus.first { it.ready && (it.generation > failedState.generation || !failedState.ready) }
                retried = true
                retry()
            }
        }
    }
}

@Composable internal fun networkReconnecting(): Boolean {
    val app = LocalContext.current.applicationContext as? NoveliaApplication ?: return false
    val state by app.ech.networkStatus.collectAsStateWithLifecycle()
    return state.recovering > 0
}
