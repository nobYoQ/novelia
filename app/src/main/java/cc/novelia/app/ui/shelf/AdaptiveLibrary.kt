package cc.novelia.app.ui.shelf

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.ui.book.BookScreen
import cc.novelia.app.ui.components.EmptyState
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.navigation.SHOW_SHELF_LIST
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** One shelf destination owns the selection, so reading and tab navigation can restore it. */
@Composable fun AdaptiveLibraryScreen(c: AppController, navigationState: SavedStateHandle, onCompactDetailChanged: (Boolean) -> Unit = {}) {
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val showShelfList by navigationState.getStateFlow(SHOW_SHELF_LIST, false).collectAsStateWithLifecycle()
    LaunchedEffect(showShelfList) {
        if (showShelfList) {
            selectedKey = null
            navigationState[SHOW_SHELF_LIST] = false
        }
    }
    val books by remember(c.store) { c.store.state.map { it.books }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = remember(c.store) { c.store.state.value.books })
    LaunchedEffect(selectedKey, books) {
        val ref = selectedKey?.let { BookRef.fromKey(it) } ?: return@LaunchedEffect
        if(ref.isLocal) {
            // Restored selection is checked only after the library has finished loading.
            // Read the latest state after awaiting, not the composition's earlier snapshot.
            c.app.initialization.await()
            if(c.store.state.value.books.none { it.book.ref == ref }) selectedKey = null
        }
    }
    AdaptiveLibraryLayout(
        selectedKey = selectedKey,
        onBack = { selectedKey = null },
        onCompactDetailChanged = onCompactDetailChanged,
        shelf = { expanded ->
            ShelfScreen(c, onOpenBook = { ref ->
                // Preserve the phone's one-tap opening of a local novel. A selection made
                // on a larger display still becomes a full detail page when resized.
                if(ref.isLocal && !expanded) c.book(ref) else selectedKey = ref.key
            }, selectedBookKey = selectedKey)
        },
        detail = { key -> BookScreen(c, BookRef.fromKey(key), onBack = { selectedKey = null }) },
    )
}

/** Move live pane compositions on resize; save hidden panes when navigating between books. */
@Composable internal fun AdaptiveLibraryLayout(
    selectedKey: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onCompactDetailChanged: (Boolean) -> Unit = {},
    shelf: @Composable (expanded: Boolean) -> Unit,
    detail: @Composable (String) -> Unit,
) {
    val state = rememberSaveableStateHolder()
    val currentShelf by rememberUpdatedState(shelf)
    val currentDetail by rememberUpdatedState(detail)
    val shelfPane = remember(state) {
        movableContentOf<Boolean> { expanded -> state.SaveableStateProvider("shelf") { currentShelf(expanded) } }
    }
    val detailPane = remember(state) {
        movableContentOf<String> { key -> state.SaveableStateProvider("detail:$key") { currentDetail(key) } }
    }
    BackHandler(enabled = selectedKey != null, onBack = onBack)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val expanded = maxWidth >= 840.dp
        val shelfWidth = (maxWidth * .42f).coerceIn(360.dp, 460.dp)
        SideEffect { onCompactDetailChanged(!expanded && selectedKey != null) }
        if(expanded) {
            Row(Modifier.fillMaxSize().testTag("library-dual-pane")) {
                Box(Modifier.width(shelfWidth).fillMaxHeight().testTag("library-shelf-pane")) {
                    shelfPane(true)
                }
                VerticalDivider()
                Box(Modifier.weight(1f).fillMaxHeight().testTag("library-detail-pane")) {
                    if(selectedKey != null) detailPane(selectedKey)
                    else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState("选择一本书", "点击左侧书籍，在这里查看详情与目录。")
                    }
                }
            }
        } else Box(Modifier.fillMaxSize().testTag(if(selectedKey == null) "library-shelf-only" else "library-detail-only")) {
            if(selectedKey == null) shelfPane(false) else detailPane(selectedKey)
        }
    }
}
