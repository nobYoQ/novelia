package cc.novelia.app.ui.shelf

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.ui.components.base.*
import cc.novelia.app.ui.components.book.BookRow
import cc.novelia.app.ui.components.book.bookRowStatus

/** 列表只负责选择交互；云端请求和待同步操作仍由账户层管理。 */
@Composable internal fun CloudShelfBooks(
    books: List<BookCard>, local: LibraryState, account: String, listState: LazyListState,
    page: Int, pageCount: Int, onPage: (Int) -> Unit,
    managing: Boolean, onManaging: (Boolean) -> Unit, selection: Map<String, BookCard>, onSelection: (Map<String, BookCard>) -> Unit,
    busy: Boolean, progress: Int, active: Boolean, controlsHeight: Int, selectedBookKey: String?,
    onOpenBook: (BookRef) -> Unit, onLocal: () -> Unit, onRemove: () -> Unit, onRetry: () -> Unit,
    grid: Boolean = false,
    gridState: LazyListState? = null,
    metadata: @Composable (BookCard) -> BookCard = { it }, actions: @Composable (BookCard, () -> Boolean) -> Unit = { _, _ -> },
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val density = LocalDensity.current
    val keys = books.map { it.ref.key }
    val columns = shelfGridColumns(maxWidth)
    val gridRows = remember(books, columns) { books.chunked(columns) }
    val layout = rememberShelfLayoutState(grid, keys, keys, columns, listState = listState, restoredGridState = gridState)
    val gridHit = rememberShelfGridHitTest(layout.grid, gridRows.map { row -> row.map { it.ref.key } }, columns)
    val dragSelection = rememberShelfDragSelection(layout.current(grid), keys, selection.keys, managing, active && !busy,
        onBegin = { onManaging(true) }, onSelection = { chosen ->
            // 另一页的选择不能因本页开始拖选而丢失。
            onSelection((selection + books.associateBy { it.ref.key }).filterKeys { it in chosen })
        }, hitTest = if(grid) gridHit else null)
    fun toggle(book: BookCard) {
        if(!busy && !dragSelection.dragging) onSelection(if(book.ref.key in selection) selection - book.ref.key else selection + (book.ref.key to book))
    }
    Column(Modifier.fillMaxSize()) {
        if(managing) {
            val allSelected = keys.isNotEmpty() && selection.keys.containsAll(keys)
            ShelfSelectionHeader(selection.size, !busy, if(dragSelection.dragging) with(density) { controlsHeight.toDp() } else 48.dp,
                onSelectAll = { onSelection(if(allSelected) selection - keys.toSet() else selection + books.associateBy { it.ref.key }) },
                onFinish = { onManaging(false); onSelection(emptyMap()) }, tagPrefix = "cloud", selectAllLabel = if(allSelected) "取消本页全选" else "全选本页")
        }
        ShelfLayoutTransition(grid, Modifier.weight(1f)) { showGrid ->
        AppLazyColumn(state = layout.current(showGrid), modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(if(showGrid) 14.dp else 0.dp),
            contentPadding = PaddingValues(top = if(showGrid) 8.dp else 0.dp, bottom = 12.dp),
            listModifier = Modifier.testTag(if(showGrid) "cloud-books-grid" else "cloud-books")
                .shelfDragSelection(dragSelection, active && !busy && showGrid == grid)) {
            if(books.isEmpty()) item {
                EmptyState("没有匹配的收藏", "可调整筛选、切换收藏夹，或在书籍详情中添加云端收藏。", action = "重新加载", onAction = onRetry)
            }
            if(showGrid) items(gridRows, key = { it.first().ref.key }, contentType = { "cover-row" }) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { book -> key(book.ref.key) {
                        val displayed = metadata(book)
                        val open = { if(!dragSelection.dragging && !busy) { if(managing) toggle(displayed) else onOpenBook(book.ref) } }
                        ShelfCoverCard(displayed, bookRowStatus(displayed, local.books.firstOrNull { it.book.ref == book.ref },
                            local.positions[book.ref.key], local.bookUpdates[book.ref.key], account, preferCloud = true), open,
                            Modifier.weight(1f).testTag("cloud-book-${book.ref.key}").semantics {
                                selected = if(managing) book.ref.key in selection else book.ref.key == selectedBookKey
                                onClick(if(managing) "切换选择" else "打开书籍") { open(); true }
                                if(active && !busy) onLongClick("选择${book.title}") { dragSelection.selectOne(book.ref.key); true }
                            }, selecting = managing, selected = if(managing) book.ref.key in selection else book.ref.key == selectedBookKey, enabled = !busy,
                            footer = if(!managing) ({ actions(displayed) { !dragSelection.dragging && !busy } }) else null)
                    } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            } else items(books, key = { it.ref.key }, contentType = { "book" }) { book ->
                val displayed = metadata(book)
                val chosen = if(managing) book.ref.key in selection else book.ref.key == selectedBookKey
                val open = { if(!dragSelection.dragging && !busy) { if(managing) toggle(displayed) else onOpenBook(book.ref) } }
                BookRow(displayed, open,
                    Modifier.testTag("cloud-book-${book.ref.key}").semantics {
                        selected = chosen
                        onClick(if(managing) "切换选择" else "打开书籍") { open(); true }
                        if(active && !busy) onLongClick("选择${book.title}") { dragSelection.selectOne(book.ref.key); true }
                    }.then(if(chosen) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier),
                    status = bookRowStatus(displayed, local.books.firstOrNull { it.book.ref == book.ref }, local.positions[book.ref.key], local.bookUpdates[book.ref.key], account, preferCloud = true),
                    compactMetadata = true, trailing = {
                        if(managing) AppCheckbox(book.ref.key in selection, { toggle(displayed) }, enabled = !busy,
                            modifier = Modifier.semantics { contentDescription = "选择${book.title}" })
                        else actions(displayed) { !dragSelection.dragging && !busy }
                    })
            }
            item { PageControls(page, pageCount) { if(!busy && !dragSelection.dragging) onPage(it) } }
        }
        }
        if(managing) CloudSelectionActions(selection.size, busy, progress, onLocal, onRemove, { onSelection(emptyMap()) })
    }
    }
}

@Composable internal fun CloudSelectionActions(count: Int, busy: Boolean, progress: Int, onLocal: () -> Unit, onRemove: () -> Unit, onClear: () -> Unit) {
    val enabled = count > 0 && !busy
    if(busy) Text("正在处理 $progress / $count 本…", Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    ShelfSelectionBar("cloud") {
        ShelfActionButton("加入本地", "加入本地收藏", Icons.Outlined.LibraryAdd, "cloud-selection-local", enabled, onLocal)
        ShelfActionButton("清空选择", "清空选择", Icons.Outlined.Deselect, "cloud-selection-clear", enabled, onClear)
        ShelfActionButton("取消收藏", "取消云端收藏", Icons.Outlined.BookmarkRemove, "cloud-selection-remove", enabled, onRemove)
    }
}
