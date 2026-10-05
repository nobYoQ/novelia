@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cc.novelia.app.ui.account

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.model.Profile
import cc.novelia.app.ui.components.AppDropdownMenu
import cc.novelia.app.ui.components.AppLazyColumn
import cc.novelia.app.ui.components.ConfirmDialog
import cc.novelia.app.ui.components.MenuRow
import cc.novelia.app.ui.components.Screen
import cc.novelia.app.ui.components.SectionTitle
import cc.novelia.app.ui.components.displayDate
import cc.novelia.app.ui.components.FilterPanelExpandIcon
import cc.novelia.app.ui.components.FilterPanelVisibility
import cc.novelia.app.ui.feedback.MidoriCompanion
import cc.novelia.app.ui.navigation.AppController

@Composable fun ProfileScreen(c: AppController) {
    val profile by c.session.profile.collectAsStateWithLifecycle(); val state by c.store.state.collectAsStateWithLifecycle(); var logout by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val companionVisible by remember { derivedStateOf {
        listState.layoutInfo.visibleItemsInfo.any { it.key == "profile-card" }
    } }
    Screen("我的") { padding -> AppLazyColumn(Modifier.padding(padding), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "profile-card") { ProfileAccountCard(profile, companionVisible,
            onLogin = { c.go("login") }, onLogout = { logout = true }, modifier = Modifier.padding(20.dp)) }
        item { SectionTitle("阅读资料") }
        item { MenuRow("书架更新", "新增章节、译文与分卷", Icons.Outlined.NewReleases, { c.go("updates") }) }
        item { MenuRow("下载管理", "查看进度、导出与离线阅读", Icons.Outlined.Download, { c.go("downloads") }) }
        item { MenuRow("书签与笔记", "${state.notes.size} 条阅读记录", Icons.Outlined.EditNote, { c.go("notes") }) }
        item { MenuRow("阅读历史", "接着上次的位置阅读", Icons.Outlined.History, { c.go("history") }) }
        item { MenuRow("文件工具", "EPUB 转 TXT、图片压缩、文本换行整理与片假名统计", Icons.Outlined.Handyman, { c.go("tools") }) }
        item { SectionTitle("偏好与数据") }
        item { MenuRow("设置", "阅读、外观、下载与数据管理", Icons.Outlined.Tune, { c.go("settings") }) }
        item { MenuRow("屏蔽管理", "管理作品和标签屏蔽", Icons.Outlined.Block, { c.go("blocked") }) }
        val pending = state.pending.count { it.account == profile?.username }
        item { MenuRow("同步状态", if(pending > 0) "$pending 项待处理 · 查看原因与重试" else "自动同步、状态与重试", Icons.Outlined.Sync, { c.go("sync") }) }
        item { MenuRow("阅读资料备份", "书架、进度、笔记、本地小说与标签词典", Icons.Outlined.Backup, { c.go("backup") }) }
        item { SectionTitle("帮助") }
        item { MenuRow("帮助与关于", "使用说明、版本与反馈", Icons.Outlined.Info, { c.go("about") }) }
    } }
    if(logout) ConfirmDialog("退出当前账号？", "本地小说、下载和笔记仍保留在此设备。云端操作需要重新登录。", { logout = false }, confirmLabel = "退出登录") { c.action("已退出登录") { try { c.session.logout() } finally { c.forumSession.clear() } } }
}

@Composable internal fun ProfileAccountCard(profile: Profile?, companionVisible: Boolean,
    onLogin: () -> Unit, onLogout: () -> Unit, modifier: Modifier = Modifier) {
    var accountMenu by remember(profile?.username) { mutableStateOf(false) }
    Card(modifier.fillMaxWidth().testTag("profile-account-card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(end = if(profile != null) 40.dp else 0.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MidoriCompanion(Modifier.size(76.dp), visible = companionVisible)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val role = profile?.let { mapOf("admin" to "管理员", "member" to "普通成员", "trusted" to "可信成员",
                            "restricted" to "受限账号", "banned" to "被封禁账号")[it.role] ?: it.role }
                        Text(if(profile == null) "阅读账户" else "$role",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(profile?.username ?: "你好!", style = MaterialTheme.typography.headlineSmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(profile?.let { "注册于 ${displayDate(it.createdAt)}" } ?: "在此设备阅读，也可以连接Novelia账号。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if(profile != null) Box(Modifier.align(Alignment.TopEnd)) {
                    IconButton(onClick = { accountMenu = true }, modifier = Modifier.size(48.dp).testTag("profile-account-menu")) {
                        Icon(Icons.Outlined.MoreHoriz, "账号操作")
                    }
                    AppDropdownMenu(accountMenu, { accountMenu = false }) {
                        DropdownMenuItem(text = { Text("退出登录") }, onClick = { accountMenu = false; onLogout() },
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Logout, null) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("profile-logout"))
                    }
                }
            }
            if(profile == null) Button(onClick = onLogin, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("登录 / 注册") }
            else AccountPermissionsCard(profile.username, profile.canPost, profile.canEdit)
        }
    }
}

@Composable internal fun AccountPermissionsCard(username: String, canPost: Boolean, canEdit: Boolean) {
    var expanded by remember(username) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()
            .heightIn(min = 48.dp).testTag("account-permissions-toggle")
            .semantics { stateDescription = if(expanded) "已展开" else "已收起" },
            contentPadding = PaddingValues(vertical = 12.dp)) {
            Icon(Icons.Outlined.VerifiedUser, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("账号权限", Modifier.weight(1f))
            Spacer(Modifier.width(4.dp))
            FilterPanelExpandIcon(expanded)
        }
        FilterPanelVisibility(expanded) {
            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface.copy(alpha = .8f)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PermissionStatus("社区发布", canPost)
                    PermissionStatus("书籍编辑", canEdit)
                }
            }
        }
    }
}

@Composable private fun PermissionStatus(label: String, available: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Icon(if(available) Icons.Outlined.CheckCircle else Icons.Outlined.Lock, null, Modifier.size(18.dp),
            tint = if(available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if(available) "可用" else "受限", style = MaterialTheme.typography.labelMedium)
    }
}
