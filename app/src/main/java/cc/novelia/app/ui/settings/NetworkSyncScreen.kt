package cc.novelia.app.ui.settings

import cc.novelia.app.ui.components.base.AppButton
import cc.novelia.app.ui.components.base.AppTextButton
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.auth.SessionBinding
import cc.novelia.app.data.auth.SessionChangedException
import cc.novelia.app.data.model.PendingAction
import cc.novelia.app.data.sync.CloudSyncStatus
import cc.novelia.app.data.sync.pendingBookKey
import cc.novelia.app.data.sync.removePendingForSession
import cc.novelia.app.data.sync.synchronizePending
import cc.novelia.app.ui.account.*
import cc.novelia.app.ui.components.base.ConfirmDialog
import cc.novelia.app.ui.components.base.syncTime
import cc.novelia.app.ui.navigation.AppController

/** “我的”、设置分类和旧的网络深链共用同一页面。 */
@Composable fun NetworkSyncScreen(c: AppController, onBack: () -> Unit = c::back) {
    val state by c.store.state.collectAsStateWithLifecycle()
    val profile by c.session.profile.collectAsStateWithLifecycle()
    val account = profile?.username
    val pending = remember(state.pending, account) { state.pending.filter { it.account == account } }
    val status = state.syncStatus[account] ?: CloudSyncStatus()
    val inFlight by c.api.cloudMutations.inFlight.collectAsStateWithLifecycle()
    val running = inFlight.values.any { it.account == account }
    var busy by remember(account) { mutableStateOf(false) }
    var removing by remember(account) { mutableStateOf<Pair<PendingAction, SessionBinding>?>(null) }
    var echSettings by remember { mutableStateOf(false) }
    val webDavConfig by c.app.webDavConfig.config.collectAsStateWithLifecycle()
    val webDavStatus by c.app.webDav.status.collectAsStateWithLifecycle()
    ProfileDetailScreen("网络与同步", onBack) { padding ->
        ProfileDetailList(Modifier.padding(padding).testTag("network-sync-list")) {
            item(key = "account-status") {
                ProfileDetailCard(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.CloudSync, null, Modifier.size(28.dp))
                        Text("原站账号同步", style = MaterialTheme.typography.titleLarge)
                        if(account == null) {
                            Text("登录后同步收藏和阅读进度。", style = MaterialTheme.typography.bodyMedium)
                            AppButton(onClick = { c.go("login") }) { Text("登录") }
                        } else {
                            Text("当前账号：$account", style = MaterialTheme.typography.titleMedium)
                            Text(when {
                                busy || running -> "正在同步…"
                                status.requiresLogin -> "需要重新登录"
                                pending.isNotEmpty() -> "待同步 ${pending.size} 项"
                                else -> "没有待同步操作"
                            }, style = MaterialTheme.typography.bodyLarge)
                            Text("最近成功：${syncTime(status.lastSuccessAt)}\n最近尝试：${syncTime(status.lastAttemptAt)}",
                                style = MaterialTheme.typography.bodySmall)
                            if(status.requiresLogin) AppButton(onClick = { c.go("login") }) { Text("重新登录") }
                            AppButton(onClick = {
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
                            }, enabled = !busy && !running && pending.isNotEmpty()) {
                                Text(if(busy || running) "正在同步…" else "立即重试")
                            }
                        }
                    }
                }
            }
            item(key = "automatic-sync") { ProfileToggle("联网自动同步", "自动重试当前账号的收藏和阅读进度", state.autoSync) { enabled -> c.store.update { it.copy(autoSync = enabled) } } }
            item { ProfileSectionTitle("连接与多设备同步") }
            item(key = "webdav") { ProfileMenuRow("WebDAV 多设备同步", when {
                webDavStatus.running -> "正在同步…"
                webDavConfig.endpoint.isBlank() -> "配置服务器与同步内容"
                !webDavConfig.enabled -> "已停用"
                webDavStatus.recovery?.matches(webDavConfig) == true -> "同步目录需要处理"
                webDavStatus.error != null -> "同步异常 · 查看详情"
                c.app.webDav.needsConfirmation() -> "等待确认同步资料"
                else -> "最近成功：${syncTime(webDavStatus.lastSuccessAt)}"
            }, Icons.Outlined.Devices, { c.go("webdav") }) }
            item(key = "book-source") { BookSourcePreference(c) }
            item(key = "network-diagnostics") { ProfileMenuRow("网络诊断与日志", "ECH、连接检测与日志导出", Icons.Outlined.Wifi, { echSettings = true }) }
            item { ProfileSectionTitle("应用更新") }
            item(key = "app-updates") { ProfileToggle("启动时检查应用更新", "关闭后仍可在帮助与关于中手动检查", state.autoCheckAppUpdates) { value -> c.store.update { it.copy(autoCheckAppUpdates = value) } } }
            if(account != null && pending.isNotEmpty()) {
                item { ProfileSectionTitle("待同步操作 · ${pending.size}") }
                items(pending, key = { "pending-${it.id}" }) { action ->
                    val book = state.books.firstOrNull { it.book.ref.key == pendingBookKey(action) }
                    val operation = when { action.path.startsWith("user/read-history/") -> "阅读进度"; action.method == "DELETE" -> "移除云端收藏"; else -> "保存云端收藏" }
                    ProfileDetailCard(Modifier.testTag("pending-sync-${action.id}")) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(book?.book?.title ?: "作品操作", style = MaterialTheme.typography.titleMedium)
                            Text(operation, style = MaterialTheme.typography.labelLarge)
                            Text(if (action.id in inFlight) "正在同步…" else status.failures[action.id] ?: if(state.autoSync) "等待联网自动同步" else "等待手动同步", style = MaterialTheme.typography.bodyMedium)
                            AppTextButton(onClick = {
                                val binding = c.session.capture()
                                if(binding.account == account) removing = action to binding
                            }, enabled = !busy && !running) { Text("移除此操作") }
                        }
                    }
                }
            }
        }
    }
    if(echSettings) EchSettings(c.app.ech) { echSettings = false }
    removing?.let { (action, binding) -> ConfirmDialog("移除待同步操作？", "这只会移除尚未发送的记录。已经到达原站的请求无法撤回，本地阅读资料仍保留。", { removing = null }, confirmLabel = "移除待同步操作") {
        removing = null
        if(c.session.capture() != binding) c.message("登录账号已变化，请重新操作")
        else c.store.update { it.removePendingForSession(action, binding, c.session.capture()) }
    } }
}
