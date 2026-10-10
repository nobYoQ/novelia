package cc.novelia.app.ui.community

import cc.novelia.app.ui.components.AppLinearProgressIndicator

import cc.novelia.app.ui.components.AppTextButton
import cc.novelia.app.ui.components.AppIconButton

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.components.AppScrollColumn
import cc.novelia.app.ui.components.Screen
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.data.model.ForumCommunityRules
import cc.novelia.app.data.model.ForumRuleTable
import cc.novelia.app.data.model.bundledForumCommunityRules
import kotlinx.coroutines.CancellationException

/** 先显示本地副本，进入页面时检查原站更新；同步失败仍可离线阅读。 */
@Composable fun ForumRulesScreen(c: AppController) {
    var document by remember { mutableStateOf(bundledForumCommunityRules) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("正在检查原站更新…") }
    LaunchedEffect(refresh) {
        loading = true
        document = c.app.forumCommunityRules.cached()
        status = "正在检查原站更新…"
        try {
            document = c.app.forumCommunityRules.refresh()
            status = "已与原站同步"
        } catch(error: CancellationException) { throw error }
        catch(_: Exception) { status = if(document.commitSha.isEmpty()) "同步未完成，显示内置离线守则" else "同步未完成，显示上次保存的守则" }
        finally { loading = false }
    }
    Screen("社区守则", c::back, actions = {
        AppIconButton(onClick = { refresh++ }, enabled = !loading) { Icon(Icons.Outlined.Refresh, "更新社区守则") }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(status, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if(loading) AppLinearProgressIndicator(Modifier.fillMaxWidth())
            ForumRulesContent(document = document) { c.requireForumLogin { c.go("forum-strikes") } }
        }
    }
}

@Composable internal fun ForumRulesContent(modifier: Modifier = Modifier, document: ForumCommunityRules = bundledForumCommunityRules, onStrikes: () -> Unit) {
    AppScrollColumn(modifier = modifier.fillMaxSize().testTag("forum-rules-page"),
        contentModifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AppTextButton(onClick = onStrikes) { Text("查看处罚记录") }
        document.blocks.forEachIndexed { index, block ->
            if(block.table != null) ForumPermissionsContent(block.table)
            else if(index != 0 || !block.heading || block.text != "社区守则") {
                Text(block.text, style = if(block.heading) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** 将网页权限表按操作展示，窄屏及大字号无需横向滚动即可读完每种账号权限。 */
@Composable private fun ForumPermissionsContent(table: ForumRuleTable) {
    table.rows.forEachIndexed { index, permission ->
        OutlinedCard(Modifier.fillMaxWidth().testTag("forum-rule-permission-$index")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${permission.site} · ${permission.operation}", style = MaterialTheme.typography.titleSmall)
                permission.allowed.forEachIndexed { column, allowed ->
                    Text("${table.headers[column + 2]}：${if(allowed) "允许" else "不允许"}",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
