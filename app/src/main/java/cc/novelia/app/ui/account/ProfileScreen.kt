@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cc.novelia.app.ui.account

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.ui.components.AppLazyColumn
import cc.novelia.app.ui.components.ConfirmDialog
import cc.novelia.app.ui.components.MenuRow
import cc.novelia.app.ui.components.MetaParagraph
import cc.novelia.app.ui.components.Screen
import cc.novelia.app.ui.components.displayDate
import cc.novelia.app.ui.feedback.MidoriCompanion
import cc.novelia.app.ui.navigation.AppController

@Composable fun ProfileScreen(c: AppController) {
    val profile by c.session.profile.collectAsStateWithLifecycle(); val state by c.store.state.collectAsStateWithLifecycle(); var logout by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val companionVisible by remember { derivedStateOf {
        listState.layoutInfo.visibleItemsInfo.any { it.key == "profile-card" }
    } }
    Screen("我的") { padding -> AppLazyColumn(Modifier.padding(padding), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "profile-card") { Card(Modifier.fillMaxWidth().padding(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MidoriCompanion(visible = companionVisible)
                Text(profile?.username ?: "你好，阅读者", style = MaterialTheme.typography.headlineMedium)
                Text(if(profile == null) "在此设备阅读，也可以连接原站账号。" else "${mapOf("admin" to "管理员", "member" to "普通成员", "trusted" to "可信成员", "restricted" to "受限账号", "banned" to "被封禁账号")[profile?.role] ?: profile?.role} · 注册于 ${displayDate(profile!!.createdAt)}", style = MaterialTheme.typography.bodyMedium)
                if(profile == null) Button(onClick = { c.go("login") }) { Text("登录 / 注册") } else TextButton(onClick = { logout = true }) { Text("退出登录") }
            }
        } }
        if(profile != null) item { MetaParagraph("账号权限", "社区发布：${if(profile!!.canPost) "可用" else "受限"}\n书籍编辑：${if(profile!!.canEdit) "可用" else "需要符合原站角色与注册时间要求"}\n操作最终由原站服务器校验。") }
        item { MenuRow("下载管理", "查看进度、导出与离线阅读", Icons.Outlined.Download, { c.go("downloads") }) }
        item { MenuRow("书签与笔记", "${state.notes.size} 条阅读记录", Icons.Outlined.EditNote, { c.go("notes") }) }
        item { MenuRow("文件工具", "EPUB 转 TXT、图片压缩、文本换行整理与片假名统计", Icons.Outlined.Handyman, { c.go("tools") }) }
        item { MenuRow("阅读与外观", "字号、主题、动效与朗读", Icons.Outlined.Tune, { c.go("settings") }) }
        item { MenuRow("屏蔽管理", "管理作品和标签屏蔽", Icons.Outlined.Block, { c.go("blocked") }) }
        val pending = state.pending.count { it.account == profile?.username }
        item { MenuRow("同步状态", if(pending > 0) "$pending 项待处理 · 查看原因与重试" else "自动同步、状态与重试", Icons.Outlined.Sync, { c.go("sync") }) }
        item { MenuRow("阅读资料备份", "书架、进度、笔记、本地小说与标签词典", Icons.Outlined.Backup, { c.go("backup") }) }
        item { MenuRow("书架更新", "新增章节、译文与分卷", Icons.Outlined.NewReleases, { c.go("updates") }) }
        item { MenuRow("帮助与关于", "使用说明、版本与反馈", Icons.Outlined.Info, { c.go("about") }) }
    } }
    if(logout) ConfirmDialog("退出当前账号？", "本地小说、下载和笔记仍保留在此设备。云端操作需要重新登录。", { logout = false }) { c.action("已退出登录") { c.session.logout() } }
}
