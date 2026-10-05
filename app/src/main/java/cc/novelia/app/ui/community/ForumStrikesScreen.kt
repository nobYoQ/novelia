package cc.novelia.app.ui.community

import cc.novelia.app.ui.components.AppLazyColumn
import cc.novelia.app.ui.components.AsyncContent
import cc.novelia.app.ui.components.EmptyState
import cc.novelia.app.ui.components.PageControls
import cc.novelia.app.ui.components.Screen
import cc.novelia.app.ui.navigation.AppController
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.model.ForumStrike
import cc.novelia.app.data.model.ForumStrikeReadState
import cc.novelia.app.ui.components.friendlyMessage
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable internal fun ForumStrikesScreen(c: AppController) {
    val profile by c.forumSession.profile.collectAsStateWithLifecycle()
    val binding = c.forumSession.capture()
    var page by rememberSaveable(binding) { mutableIntStateOf(0) }
    Screen("处罚记录", c::back) { padding ->
        if(profile == null) Box(Modifier.padding(padding)) {
            EmptyState("登录后查看处罚记录", "使用论坛账号查看处罚依据和撤销状态。", Icons.Outlined.Gavel, "登录论坛", { c.go("forum-login") })
        } else AsyncContent(listOf(binding, page), load = { c.forumAccountApi.strikes(page, binding) }, modifier = Modifier.padding(padding)) { result, reload ->
            Column {
                ForumStrikeReadConfirmation(result.latestStrikeId, binding, { c.forumAccountApi.markStrikesRead(it, binding) }, reload)
                AppLazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item { Text("查看账号处罚及其撤销状态", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if(result.items.isEmpty()) item { EmptyState("暂无处罚记录", "你的账号目前没有处罚记录。", Icons.Outlined.Gavel) }
                    items(result.items, key = { it.id }) { ForumStrikeCard(it) }
                    item { PageControls(page, result.pageCount()) { page = it } }
                }
            }
        }
    }
}

@Composable internal fun ForumStrikeReadConfirmation(latestStrikeId: Long?, sessionKey: Any?,
    acknowledge: suspend (Long) -> ForumStrikeReadState, onReload: () -> Unit) {
    var message by remember(latestStrikeId, sessionKey) { mutableStateOf<String?>(null) }
    var failed by remember(latestStrikeId, sessionKey) { mutableStateOf(false) }
    var attempt by remember(latestStrikeId, sessionKey) { mutableIntStateOf(0) }
    val currentAcknowledge by rememberUpdatedState(acknowledge)
    LaunchedEffect(latestStrikeId, sessionKey, attempt) {
        latestStrikeId?.let { latest ->
            try {
                val state = currentAcknowledge(latest)
                failed = false
                message = if(state.hasUnread) "还有新处罚记录，请刷新查看。" else null
            } catch(error: CancellationException) { throw error }
            catch(error: Exception) { failed = true; message = "记录已展示，标记已读未完成：${error.friendlyMessage()}" }
        }
    }
    message?.let { text -> Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { if(failed) attempt++ else onReload() }) { Text(if(failed) "重试标记已读" else "刷新记录") }
    } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ForumStrikeCard(strike: ForumStrike) {
    val revoked = strike.revokedAt != null
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(strike.reason, style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = MaterialTheme.shapes.small, color = if(revoked) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.errorContainer) {
                    Text(if(revoked) "已撤销" else "生效中", Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
                }
                Text("${strike.point} 分", Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
            }
            Text("${strikeTime(strike.createdEpoch)} · 记录 #${strike.id}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("处罚依据", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(strike.evidence.ifBlank { "未提供" }, style = MaterialTheme.typography.bodyMedium)
                }
            }
            strike.revokedEpoch?.let { Text("撤销于 ${strikeTime(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private fun strikeTime(epoch: Long) = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(epoch))
