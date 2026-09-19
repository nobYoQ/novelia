package cc.novelia.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.*
import java.text.DateFormat
import java.util.Date

private fun syncTime(value: Long) = if(value <= 0) "尚无记录" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))

@Composable fun CloudSyncScreen(c: AppController) {
    val state by c.store.state.collectAsStateWithLifecycle()
    val profile by c.session.profile.collectAsStateWithLifecycle()
    val account = profile?.username
    val pending = remember(state.pending, account) { state.pending.filter { it.account == account } }
    val status = state.syncStatus[account] ?: CloudSyncStatus()
    var busy by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<PendingAction?>(null) }
    Screen("同步状态", c::back) { padding ->
        AppLazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { TogglePreference("联网自动同步", "自动重试本账号的收藏和阅读进度；系统后台调度可能延后", state.autoSync) { enabled -> c.store.update { it.copy(autoSync = enabled) } } }
            if(account == null) item { EmptyState("登录后同步阅读资料", "待同步操作按账号保留，登录其他账号不会发送它们。", action = "登录", onAction = { c.go("login") }) }
            else {
                item { MetaParagraph("当前账号：$account", "待同步 ${pending.size} 项\n最近尝试：${syncTime(status.lastAttemptAt)}\n最近成功：${syncTime(status.lastSuccessAt)}") }
                if(status.requiresLogin) item { MenuRow("重新登录", "登录会话已失效，重新登录后可重试", Icons.Outlined.Login, { c.go("login") }) }
                item { Button(onClick = {
                    busy = true
                    c.action {
                        try {
                            val result = synchronizePending(c.app, manual = true)
                            val remaining = c.store.state.value.pending.count { it.account == account }
                            c.message(if(remaining == 0) "同步完成" else "已同步 ${result.completed} 项，剩余 $remaining 项请查看下方原因")
                        } finally { busy = false }
                    }
                }, enabled = !busy && pending.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) { Text(if(busy) "正在同步…" else "立即重试") } }
                if(pending.isEmpty()) item { EmptyState("没有待同步操作", "收藏和阅读进度已提交；本地文件和笔记可通过阅读资料备份迁移。", Icons.Outlined.CloudDone) }
                items(pending, key = { it.id }) { action ->
                    val book = state.books.firstOrNull { action.path.endsWith("/${it.book.ref.key}") || (it.book.ref.isWenku && action.path.endsWith("/${it.book.ref.id}")) }
                    val operation = when { action.path.startsWith("user/read-history/") -> "阅读进度"; action.method == "DELETE" -> "移除云端收藏"; else -> "保存云端收藏" }
                    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(book?.book?.title ?: "作品操作", style = MaterialTheme.typography.titleMedium)
                            Text(operation, style = MaterialTheme.typography.labelLarge)
                            Text(status.failures[action.id] ?: if(state.autoSync) "等待联网自动同步" else "等待手动同步", style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { removing = action }, enabled = !busy) { Text("移除此操作") }
                        }
                    }
                }
            }
        }
    }
    removing?.let { action -> ConfirmDialog("移除待同步操作？", "这只会移除尚未发送的记录。已经到达原站的请求无法撤回，本地阅读资料仍保留。", { removing = null }) {
        removing = null
        c.store.update { it.copy(pending = it.pending.filterNot { pending -> pending.id == action.id }) }
    } }
}

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
