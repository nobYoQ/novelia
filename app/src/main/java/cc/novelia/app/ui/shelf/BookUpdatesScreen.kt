package cc.novelia.app.ui.shelf

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.updates.UpdateWorker
import cc.novelia.app.ui.components.AppLazyColumn
import cc.novelia.app.ui.components.BookRow
import cc.novelia.app.ui.components.EmptyState
import cc.novelia.app.ui.components.MetaParagraph
import cc.novelia.app.ui.components.Screen
import cc.novelia.app.ui.components.syncTime
import cc.novelia.app.ui.navigation.AppController

@Composable fun BookUpdatesScreen(c: AppController) {
    val state by c.store.state.collectAsStateWithLifecycle()
    val books = remember(state.books, state.bookUpdates) { state.books.filter { it.hasUpdates }.sortedByDescending { state.bookUpdates[it.book.ref.key]?.checkedAt ?: 0 } }
    val last = state.drafts["updates:last"]?.split('|')
    Screen("书架更新", c::back, actions = {
        IconButton(onClick = { UpdateWorker.checkNow(c.app); c.message("已开始检查书架更新") }) { Icon(Icons.Outlined.Refresh, "检查更新") }
    }) { padding -> AppLazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { MetaParagraph("更新记录", "按作品列出新增章节、各引擎补齐的译文和新增分卷文件。通知采用本书首选译文引擎。" +
            (last?.firstOrNull()?.toLongOrNull()?.let { "\n最近检查：${syncTime(it)}" } ?: "") +
            (last?.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 }?.let { "\n上次有 $it 本检查失败，可稍后重试。" } ?: "")) }
        if(books.isEmpty()) item { EmptyState("暂时没有未读更新", "可点击右上角检查；自动提醒约每六小时检查一次。", Icons.Outlined.AutoStories) }
        items(books, key = { it.book.ref.key }) { saved ->
            val update = state.bookUpdates[saved.book.ref.key]
            Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    BookRow(saved.book.copy(subtitle = update?.summary?.ifBlank { null } ?: "有更新"), { c.book(saved.book.ref) })
                    TextButton(onClick = {
                        c.store.update { current -> current.copy(books = current.books.map { if(it.book.ref == saved.book.ref) it.copy(hasUpdates = false) else it }, bookUpdates = current.bookUpdates - saved.book.ref.key) }
                    }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("标记更新已读") }
                }
            }
        }
    } }
}
