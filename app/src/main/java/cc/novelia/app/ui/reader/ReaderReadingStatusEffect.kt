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

/** 末章正文到尾后立即记录完成，普通前台停留用于判断开读。 */
@Composable internal fun rememberReaderReadingStatus(
    c: AppController, ref: BookRef, chapterId: String, token: ReadingStatusToken?,
    eligible: Boolean, completeCatalog: Boolean, position: Position, atChapterEnd: () -> Boolean,
): (confirmEnd: Boolean) -> Unit {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by lifecycle.currentStateFlow.collectAsState()
    val active = eligible && state.isAtLeast(Lifecycle.State.RESUMED)
    val currentPosition by rememberUpdatedState(position)
    val currentEnd by rememberUpdatedState(atChapterEnd)
    fun canComplete() = completeCatalog && currentEnd()
    LaunchedEffect(ref, chapterId, token, active, completeCatalog) {
        if(!active || token == null) return@LaunchedEffect
        snapshotFlow { canComplete() }.collectLatest { atEnd ->
            if(!atEnd) delay(5_000)
            if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && atEnd == canComplete()) {
                c.store.update { it.withReadingActivity(ref, token, currentPosition, atEnd, completeCatalog) }
            }
        }
    }
    return { confirmEnd ->
        if(active && token != null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            (!confirmEnd || canComplete())) {
            c.store.update { it.withReadingActivity(ref, token, currentPosition, confirmEnd, completeCatalog) }
        }
    }
}
