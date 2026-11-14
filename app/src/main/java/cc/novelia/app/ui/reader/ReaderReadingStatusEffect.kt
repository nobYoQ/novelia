package cc.novelia.app.ui.reader

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import cc.novelia.app.data.library.*
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.Position
import cc.novelia.app.ui.navigation.AppController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/** 正文有效停留五秒才自动记录；拖动、定位及旧生命周期回调不能立即制造完成。 */
@Composable internal fun rememberReaderReadingStatus(
    c: AppController, ref: BookRef, chapterId: String, token: ReadingStatusToken?,
    eligible: Boolean, completeCatalog: Boolean, position: () -> Position?, atChapterEnd: () -> Boolean,
): () -> Unit {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by lifecycle.currentStateFlow.collectAsState()
    val active = eligible && state.isAtLeast(Lifecycle.State.RESUMED)
    val currentPosition by rememberUpdatedState(position)
    val currentEnd by rememberUpdatedState(atChapterEnd)
    LaunchedEffect(ref, chapterId, token, active, completeCatalog) {
        if(!active || token == null) return@LaunchedEffect
        snapshotFlow { currentEnd() }.distinctUntilChanged().collectLatest { end ->
            delay(5_000)
            currentPosition()?.let { point -> c.store.update { it.withReadingActivity(ref, token, point, end, completeCatalog) } }
        }
    }
    return {
        if(active && token != null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) currentPosition()?.let { point ->
            c.store.update { it.withReadingActivity(ref, token, point) }
        }
    }
}
