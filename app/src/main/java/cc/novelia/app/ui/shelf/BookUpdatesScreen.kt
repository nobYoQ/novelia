package cc.novelia.app.ui.shelf

import cc.novelia.app.ui.components.base.AppTextButton
import cc.novelia.app.ui.components.base.AppIconButton
import cc.novelia.app.ui.components.base.AppLinearProgressIndicator
import cc.novelia.app.ui.account.ProfileDetailCard
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.updates.ManualShelfCheck
import cc.novelia.app.data.updates.ShelfCheckReport
import cc.novelia.app.data.updates.ShelfCheckStatus
import cc.novelia.app.data.updates.withAcknowledgedBookUpdates
import cc.novelia.app.ui.account.ProfileDetailList
import cc.novelia.app.ui.components.book.BookRow
import cc.novelia.app.ui.account.ProfileEmptyState
import cc.novelia.app.ui.account.ProfileSectionTitle
import cc.novelia.app.ui.account.ProfileDetailScreen
import cc.novelia.app.ui.components.base.syncTime
import cc.novelia.app.ui.navigation.AppController

@Composable fun BookUpdatesScreen(c: AppController) {
    val state by c.store.state.collectAsStateWithLifecycle()
    val manual by remember(c.app) { ManualShelfCheck.observe(c.app) }
        .collectAsStateWithLifecycle(initialValue = ShelfCheckStatus("正在读取检查状态…", active = true))
    var requested by remember { mutableStateOf(false) }
    BookUpdatesView(state, manual, requested, c::back, onCheck = {
        if(!requested) {
            requested = true
            c.action {
                try { c.message(ManualShelfCheck.checkNow(c.app)) }
                finally { requested = false }
            }
        }
    }, onBook = c::book, onRead = { ref -> c.store.update { it.withAcknowledgedBookUpdates(ref) } })
}

@Composable internal fun BookUpdatesView(state: LibraryState, manual: ShelfCheckStatus?, requested: Boolean,
    onBack: () -> Unit, onCheck: () -> Unit, onBook: (BookRef) -> Unit, onRead: (BookRef) -> Unit) {
    val books = remember(state.books, state.bookUpdates) { state.books.filter { it.hasUpdates }.sortedByDescending { state.bookUpdates[it.book.ref.key]?.checkedAt ?: 0 } }
    val hasOnlineBooks = remember(state.books) { state.books.any { !it.book.ref.isLocal } }
    val last = ShelfCheckReport.decode(state.drafts[ShelfCheckReport.KEY])
    val status = when {
        manual?.active == true -> manual
        requested -> ShelfCheckStatus("正在提交书架检查…", active = true)
        manual != null && (last == null || manual.finishedAt >= last.finishedAt) -> manual
        last != null -> ShelfCheckStatus(last.message, finishedAt = last.finishedAt)
        hasOnlineBooks -> ShelfCheckStatus("尚未检查书架更新")
        else -> ShelfCheckStatus("没有可检查的在线书籍")
    }
    ProfileDetailScreen("书架更新", onBack, actions = {
        AppIconButton(onClick = onCheck, enabled = hasOnlineBooks && !status.active) { Icon(Icons.Outlined.Refresh, "检查更新") }
    }) { padding -> ProfileDetailList(Modifier.padding(padding)) {
        item { ProfileDetailCard {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(status.message, Modifier.semantics { if(!status.active) liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.titleMedium)
                status.progress?.let { progress -> AppLinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth()) }
                if(status.finishedAt > 0) Text("最近检查：${syncTime(status.finishedAt)}" +
                    (last?.takeIf { it.finishedAt == status.finishedAt }?.total?.let { " · 共 $it 本" } ?: ""), style = MaterialTheme.typography.bodySmall)
                if(last != null && !status.active && manual?.finishedAt == 0L) {
                    Text(manual.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                if(!hasOnlineBooks) Text("只检查已收藏的在线书籍，本地文件无需检查。", style = MaterialTheme.typography.bodyMedium)
            }
        } }
        item { ProfileSectionTitle("待查看的更新 · ${books.size} 本") }
        if(books.isEmpty()) item { ProfileEmptyState("暂时没有未读更新", "新增章节、译文和分卷会列在这里。", Icons.Outlined.AutoStories) }
        items(books, key = { it.book.ref.key }) { saved ->
            val update = state.bookUpdates[saved.book.ref.key]
            ProfileDetailCard {
                Column(Modifier.padding(vertical = 8.dp)) {
                    BookRow(saved.book.copy(subtitle = update?.summary?.ifBlank { null } ?: "有更新"), { onBook(saved.book.ref) })
                    AppTextButton(onClick = { onRead(saved.book.ref) }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("标记更新已读") }
                }
            }
        }
    } }
}
