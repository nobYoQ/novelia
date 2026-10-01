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

/** 书架导航项统一持有选中书目，使阅读返回和标签切换能够恢复选择。 */
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
            // 等书库完成加载后，再检查恢复的选中项；
            // 等待结束后读取最新状态，避免使用组合开始时的旧快照。
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
                // 手机仍保持单击打开本地小说；在大屏上选择的书籍，
                // 缩小窗口后转为完整详情页。
                if(ref.isLocal && !expanded) c.book(ref) else selectedKey = ref.key
            }, selectedBookKey = selectedKey)
        },
        detail = { key -> BookScreen(c, BookRef.fromKey(key), onBack = { selectedKey = null }) },
    )
}

/**
 * 尺寸变化时通过 movableContentOf 移动现有面板，保留仍在使用的输入和滚动状态。
 * 隐藏面板由 SaveableStateHolder 按书目键保存可恢复状态；两个机制负责不同生命周期。
 * 840 dp 起显示书架与详情双栏，更窄时显示当前选中的一栏，选择本身由导航页面持有。
 */
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
