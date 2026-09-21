package cc.novelia.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.auth.SessionBinding
import cc.novelia.app.data.auth.SessionChangedException
import cc.novelia.app.data.model.PendingAction
import cc.novelia.app.data.sync.CloudSyncStatus
import cc.novelia.app.data.sync.pendingBookKey
import cc.novelia.app.data.sync.removePendingForSession
import cc.novelia.app.data.sync.synchronizePending
import cc.novelia.app.ui.components.AppLazyColumn
import cc.novelia.app.ui.components.ConfirmDialog
import cc.novelia.app.ui.components.EmptyState
import cc.novelia.app.ui.components.MenuRow
import cc.novelia.app.ui.components.MetaParagraph
import cc.novelia.app.ui.components.Screen
import cc.novelia.app.ui.components.TogglePreference
import cc.novelia.app.ui.components.syncTime
import cc.novelia.app.ui.navigation.AppController

@Composable fun CloudSyncScreen(c: AppController) {
    val state by c.store.state.collectAsStateWithLifecycle()
    val profile by c.session.profile.collectAsStateWithLifecycle()
    val account = profile?.username
    val pending = remember(state.pending, account) { state.pending.filter { it.account == account } }
    val status = state.syncStatus[account] ?: CloudSyncStatus()
    val inFlight by c.api.cloudMutations.inFlight.collectAsStateWithLifecycle()
    val running = inFlight.values.any { it.account == account }
    var busy by remember(account) { mutableStateOf(false) }
    var removing by remember(account) { mutableStateOf<Pair<PendingAction, SessionBinding>?>(null) }
    Screen("同步状态", c::back) { padding ->
        AppLazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { TogglePreference("联网自动同步", "自动重试本账号的收藏和阅读进度；系统后台调度可能延后", state.autoSync) { enabled -> c.store.update { it.copy(autoSync = enabled) } } }
            if(account == null) item { EmptyState("登录后同步阅读资料", "待同步操作按账号保留，登录其他账号不会发送它们。", action = "登录", onAction = { c.go("login") }) }
            else {
                item { MetaParagraph("当前账号：$account", "待同步 ${pending.size} 项\n最近尝试：${syncTime(status.lastAttemptAt)}\n最近成功：${syncTime(status.lastSuccessAt)}") }
                if(status.requiresLogin) item { MenuRow("重新登录", "登录会话已失效，重新登录后可重试", Icons.Outlined.Login, { c.go("login") }) }
                item { Button(onClick = {
                    val binding = c.session.capture()
                    busy = true
                    c.action {
                        try {
                            if(binding.account != account) throw SessionChangedException()
                            val result = synchronizePending(c.app, manual = true, binding = binding)
                            c.session.ensureCurrent(binding)
                            val remaining = c.store.state.value.pending.count { it.account == binding.account }
                            c.message(if(remaining == 0) "同步完成" else "已同步 ${result.completed} 项，剩余 $remaining 项请查看下方原因")
                        } finally { busy = false }
                    }
                }, enabled = !busy && !running && pending.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) { Text(if(busy || running) "正在同步…" else "立即重试") } }
                if(pending.isEmpty()) item { EmptyState("没有待同步操作", "收藏和阅读进度已提交；本地文件和笔记可通过阅读资料备份迁移。", Icons.Outlined.CloudDone) }
                items(pending, key = { it.id }) { action ->
                    val book = state.books.firstOrNull { it.book.ref.key == pendingBookKey(action) }
                    val operation = when { action.path.startsWith("user/read-history/") -> "阅读进度"; action.method == "DELETE" -> "移除云端收藏"; else -> "保存云端收藏" }
                    Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(book?.book?.title ?: "作品操作", style = MaterialTheme.typography.titleMedium)
                            Text(operation, style = MaterialTheme.typography.labelLarge)
                            Text(if (action.id in inFlight) "正在同步…" else status.failures[action.id] ?: if(state.autoSync) "等待联网自动同步" else "等待手动同步", style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = {
                                val binding = c.session.capture()
                                if(binding.account == account) removing = action to binding
                            }, enabled = !busy && !running) { Text("移除此操作") }
                        }
                    }
                }
            }
        }
    }
    removing?.let { (action, binding) -> ConfirmDialog("移除待同步操作？", "这只会移除尚未发送的记录。已经到达原站的请求无法撤回，本地阅读资料仍保留。", { removing = null }, confirmLabel = "移除待同步操作") {
        removing = null
        if(c.session.capture() != binding) c.message("登录账号已变化，请重新操作")
        else c.store.update { it.removePendingForSession(action, binding, c.session.capture()) }
    } }
}
