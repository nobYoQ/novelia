package cc.novelia.app.ui.shelf

import cc.novelia.app.ui.components.AppTextButton
import cc.novelia.app.ui.components.AppIconButton

import cc.novelia.app.ui.account.ProfileDetailCard

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
import cc.novelia.app.data.updates.withAcknowledgedBookUpdates
import cc.novelia.app.ui.account.ProfileDetailList
import cc.novelia.app.ui.components.BookRow
import cc.novelia.app.ui.account.ProfileEmptyState
import cc.novelia.app.ui.account.ProfileSummary
import cc.novelia.app.ui.account.ProfileDetailScreen
import cc.novelia.app.ui.components.syncTime
import cc.novelia.app.ui.navigation.AppController

@Composable fun BookUpdatesScreen(c: AppController) {
    val state by c.store.state.collectAsStateWithLifecycle()
    val books = remember(state.books, state.bookUpdates) { state.books.filter { it.hasUpdates }.sortedByDescending { state.bookUpdates[it.book.ref.key]?.checkedAt ?: 0 } }
    val last = state.drafts["updates:last"]?.split('|')
    ProfileDetailScreen("书架更新", c::back, actions = {
        AppIconButton(onClick = { UpdateWorker.checkNow(c.app); c.message("已开始检查书架更新") }) { Icon(Icons.Outlined.Refresh, "检查更新") }
    }) { padding -> ProfileDetailList(Modifier.padding(padding)) {
        item { ProfileSummary("${books.size} 本有更新", "新增章节、译文与分卷文件" +
            (last?.firstOrNull()?.toLongOrNull()?.let { "\n最近检查：${syncTime(it)}" } ?: "") +
            (last?.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 }?.let { "\n上次有 $it 本检查失败，可稍后重试。" } ?: "")) }
        if(books.isEmpty()) item { ProfileEmptyState("暂时没有未读更新", "可点击右上角检查；自动提醒约每六小时检查一次。", Icons.Outlined.AutoStories) }
        items(books, key = { it.book.ref.key }) { saved ->
            val update = state.bookUpdates[saved.book.ref.key]
            ProfileDetailCard {
                Column(Modifier.padding(vertical = 8.dp)) {
                    BookRow(saved.book.copy(subtitle = update?.summary?.ifBlank { null } ?: "有更新"), { c.book(saved.book.ref) })
                    AppTextButton(onClick = {
                        c.store.update { it.withAcknowledgedBookUpdates(saved.book.ref) }
                    }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("标记更新已读") }
                }
            }
        }
    } }
}
