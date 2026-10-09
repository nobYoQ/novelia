package cc.novelia.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CompareArrows
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.webdav.*
import cc.novelia.app.ui.components.AppLazyColumn
import cc.novelia.app.ui.components.ChoiceRow
import cc.novelia.app.ui.components.syncTime
import java.net.URI

internal data class WebDavSyncActions(
    val onBack: () -> Unit = {},
    val onServer: () -> Unit = {},
    val onEnabled: (Boolean) -> Unit = {},
    val onDomain: (SyncDomain, Boolean) -> Unit = { _, _ -> },
    val onDevicePreferences: (Boolean) -> Unit = {},
    val onAutomatic: (Boolean) -> Unit = {},
    val onWifiOnly: (Boolean) -> Unit = {},
    val onInterval: (Long) -> Unit = {},
    val onSync: () -> Unit = {},
    val onPreview: () -> Unit = {},
    val onRecover: (WebDavRecovery) -> Unit = {},
    val onConflict: (SyncConflict) -> Unit = {},
    val conflictTitle: (SyncConflict) -> String = { "${webDavLabels.getValue(it.domain).first} · 查看保留的版本" },
)

private fun SyncDomain.icon(): ImageVector = when(this) {
    SyncDomain.SETTINGS -> Icons.Outlined.Tune
    SyncDomain.KEYWORDS -> Icons.AutoMirrored.Outlined.Label
    SyncDomain.FAVORITES -> Icons.Outlined.FavoriteBorder
    SyncDomain.PROGRESS -> Icons.Outlined.AutoStories
    SyncDomain.BOOKMARKS -> Icons.Outlined.BookmarkBorder
    SyncDomain.NOTES -> Icons.Outlined.EditNote
    SyncDomain.HISTORY -> Icons.Outlined.History
}

/** 独立渲染层便于离线检查布局，不需要账户、凭据或真实同步资料。 */
@Composable
internal fun WebDavSyncContent(config: WebDavConfig, status: WebDavSyncStatus, needsConfirmation: Boolean, working: Boolean, actions: WebDavSyncActions) {
    var showDetails by remember { mutableStateOf(false) }
    val recovery = status.recovery?.takeIf { it.matches(config) }
    val action = when {
        working -> "正在处理…"
        config.endpoint.isBlank() -> "配置同步服务器"
        recovery != null -> "处理同步目录"
        !config.enabled -> "启用并同步"
        needsConfirmation -> "查看资料并连接"
        else -> "立即同步"
    }
    WebDavPage("多设备同步", actions.onBack, action, Icons.Outlined.Sync,
        !working && (config.endpoint.isBlank() || recovery != null || config.selected.isNotEmpty()), working,
        { if(recovery != null) actions.onRecover(recovery) else actions.onSync() }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            AppLazyColumn(Modifier.widthIn(max = 640.dp).fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item { WebDavHero("WebDAV", "连接自己的云盘，让收藏与阅读资料在多台设备间同步。", Icons.Outlined.Devices) }
                if(recovery != null) item { WebDavCard {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("同步目录需要重新连接", style = MaterialTheme.typography.titleMedium)
                        Text(if(recovery.reason == WebDavRecoveryReason.MISSING_DATASET)
                            "找不到原来的云端同步资料。本机资料已保留，可以重新建立同步，也可以检查服务器地址和目录。"
                            else "云端目录中的同步资料已更换。本机资料已保留，重新连接前会展示合并预览。",
                            style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { actions.onRecover(recovery) }, enabled = !working) { Text("重新连接") }
                        TextButton(onClick = actions.onServer, enabled = !working) { Text("检查服务器和目录") }
                    }
                } }
                item { WebDavMenuRow("同步服务器", webDavServerLabel(config), Icons.Outlined.Dns, webDavRowShape(0, 2), enabled = !working, onClick = actions.onServer) }
                item { WebDavToggleRow("启用 WebDAV", when {
                    !config.enabled -> "已停用，选择下方要同步的内容"
                    recovery != null -> "已暂停，等待处理同步目录"
                    needsConfirmation -> "已开启，等待确认同步资料"
                    else -> "已开启，同步所选内容"
                }, Icons.Outlined.CloudSync, config.enabled, webDavRowShape(1, 2), enabled = !working || config.enabled, onCheckedChange = actions.onEnabled) }
                item { WebDavSection("同步内容") }
                SyncDomain.entries.forEachIndexed { index, domain ->
                    item(key = domain.name) {
                        val (title, description) = webDavLabels.getValue(domain)
                        WebDavToggleRow(title, description, domain.icon(), domain in config.selected,
                            webDavRowShape(index, SyncDomain.entries.size), enabled = !working) { actions.onDomain(domain, it) }
                    }
                }
                item { Text("仅同步上述资料，不包含本地文件、导入文件、本地分卷及其阅读记录。取消选择不会删除已有资料。",
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if(SyncDomain.SETTINGS in config.selected) item {
                    WebDavToggleRow("同步设备显示与翻页偏好", "亮度、电子纸模式与翻页操作通常由各设备单独设置", Icons.Outlined.DisplaySettings,
                        config.syncDevicePreferences, enabled = !working, onCheckedChange = actions.onDevicePreferences)
                }
                item { WebDavSection("自动同步") }
                item { WebDavToggleRow("自动同步", "修改后、回到应用时同步，并在后台定期检查", Icons.Outlined.Autorenew, config.automatic,
                    webDavRowShape(0, if(config.automatic) 3 else 1), enabled = !working, onCheckedChange = actions.onAutomatic) }
                if(config.automatic) {
                    item { WebDavToggleRow("仅使用非计费网络", "通常为 Wi-Fi；手动同步可使用当前网络", Icons.Outlined.Wifi, config.wifiOnly,
                        webDavRowShape(1, 3), enabled = !working, onCheckedChange = actions.onWifiOnly) }
                    item { WebDavCard(shape = webDavRowShape(2, 3)) {
                        Column(Modifier.padding(top = 8.dp, bottom = 12.dp)) {
                            ChoiceRow("后台检查间隔", listOf("15 分钟", "30 分钟", "1 小时", "6 小时"), listOf(15L, 30L, 60L, 360L).indexOf(config.intervalMinutes).coerceAtLeast(0)) {
                                if(!working) actions.onInterval(listOf(15L, 30L, 60L, 360L)[it])
                            }
                            Text("后台检查可能因系统省电而延后。", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } }
                }
                item { WebDavSection("同步状态") }
                item { WebDavCard {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(when { status.running -> "正在同步"; recovery != null -> "等待处理同步目录"; !config.enabled -> "同步已停用"; needsConfirmation -> "等待确认同步资料"; else -> "已连接同步资料" }, style = MaterialTheme.typography.titleMedium)
                        Text("最近成功：${syncTime(status.lastSuccessAt)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        status.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                        TextButton(onClick = { showDetails = !showDetails }, contentPadding = PaddingValues(0.dp)) {
                            Text(if(showDetails) "收起详细状态" else "查看详细状态")
                            Icon(if(showDetails) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                        }
                        if(showDetails) {
                            Text("最近尝试：${syncTime(status.lastAttemptAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            config.selected.sortedBy { it.ordinal }.forEach { domain ->
                                val result = status.domains[domain]
                                Text("${webDavLabels.getValue(domain).first} · " + when {
                                    result?.error != null -> result.error
                                    (result?.pendingCount ?: 0) > 0 -> "待同步 ${result?.pendingCount} 条记录"
                                    result?.pending == true -> "有本机修改等待同步"
                                    else -> syncTime(result?.lastSuccessAt ?: 0)
                                }, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                } }
                if(config.bound && recovery == null && config.enabled && config.selected.isNotEmpty()) item {
                    TextButton(onClick = actions.onPreview, enabled = !working, modifier = Modifier.fillMaxWidth()) { Text("查看并合并同步资料") }
                }
                if(status.conflicts.isNotEmpty()) {
                    item { WebDavSection("需要选择的资料") }
                    item { Text("不同设备修改的版本均已保留，请选择要使用的资料。", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    status.conflicts.forEachIndexed { index, value ->
                        item(key = "conflict-$index") { WebDavMenuRow(actions.conflictTitle(value), "查看并选择保留的版本", Icons.AutoMirrored.Outlined.CompareArrows,
                            webDavRowShape(index, status.conflicts.size), enabled = !working) { actions.onConflict(value) } }
                    }
                }
            }
        }
    }
}

private fun webDavServerLabel(config: WebDavConfig): String =
    if(config.endpoint.isBlank()) "填写云盘或服务器的连接信息"
    else runCatching { URI(config.endpoint).host }.getOrNull()?.takeIf(String::isNotBlank) ?: "已配置同步服务器"
