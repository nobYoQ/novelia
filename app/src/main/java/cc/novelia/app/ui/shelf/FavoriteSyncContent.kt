package cc.novelia.app.ui.shelf

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.library.*
import cc.novelia.app.ui.components.base.*
import cc.novelia.app.ui.theme.appReducedMotion
import cc.novelia.app.ui.theme.appRoundedCornerShape
import kotlin.math.roundToInt

@Composable internal fun FavoriteSyncHeader(title: String, subtitle: String, onClose: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = appRoundedCornerShape(14.dp), color = colors.primaryContainer, contentColor = colors.onPrimaryContainer) {
            Icon(Icons.Outlined.CloudSync, null, Modifier.padding(10.dp).size(24.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        if(onClose != null) AppIconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "关闭收藏同步") }
    }
    HorizontalDivider(color = colors.outlineVariant.copy(alpha = .5f))
}

@Composable internal fun FavoriteSyncSetup(priority: FavoriteSyncPriority, error: String?, onSelect: (FavoriteSyncPriority) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("同步基准", style = MaterialTheme.typography.titleSmall)
        Text(if(priority == FavoriteSyncPriority.Local) "以本地分组为准，整理云端收藏。" else "以云端分组为准，整理本地书架。",
            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        PanelRadioGrid(listOf(FavoriteSyncPriority.Local to "优先本地", FavoriteSyncPriority.Cloud to "优先云端"), priority, onSelect)
    }
    if(error != null) SyncNotice(Icons.Outlined.ErrorOutline, "未完成同步", error, error = true)
}

@Composable internal fun FavoriteSyncEmpty() {
    SyncNotice(Icons.Outlined.CheckCircleOutline, "无需同步", "基准一侧的书籍都已归入对应收藏夹。")
}

@Composable private fun SyncNotice(icon: ImageVector, title: String, detail: String, error: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = appRoundedCornerShape(18.dp), color = if(error) colors.errorContainer else colors.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(22.dp), tint = if(error) colors.onErrorContainer else colors.primary)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = if(error) colors.onErrorContainer else colors.onSurfaceVariant)
            }
        }
    }
}

@Composable internal fun FavoriteSyncProgressCard(progress: FavoriteSyncProgress, applying: Boolean) {
    val colors = MaterialTheme.colorScheme
    val phase = progress.phase
    val title = when(phase) {
        FavoriteSyncPhase.ReadWeb -> "读取网络小说"
        FavoriteSyncPhase.ReadWenku -> "读取文库小说"
        FavoriteSyncPhase.ResolveFolders -> "核对收藏夹"
        FavoriteSyncPhase.Compare -> "比对两侧分组"
        FavoriteSyncPhase.Write -> "保存收藏变更"
    }
    val detail = when(phase) {
        FavoriteSyncPhase.ReadWeb, FavoriteSyncPhase.ReadWenku -> progress.total?.let {
            "已读 ${progress.completed} / $it 页 · ${progress.booksRead} 本"
        } ?: "正在获取第一页…"
        FavoriteSyncPhase.ResolveFolders -> when(progress.total) {
            null -> "正在读取云端收藏夹…"
            0 -> "没有需要核对归属的书籍"
            else -> "已确认 ${progress.completed} / ${progress.total} 本的归属"
        }
        FavoriteSyncPhase.Compare -> "正在核对需要新增或移动的书籍…"
        FavoriteSyncPhase.Write -> "已处理 ${progress.completed} / ${progress.total} 本"
    }
    val fraction = progress.fraction
    Surface(shape = appRoundedCornerShape(20.dp), color = colors.surfaceContainerLow,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = .5f))) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if(phase == FavoriteSyncPhase.Write) "执行已确认的清单"
                else "${if(applying) "复核清单" else "检查差异"} · 步骤 ${phase.ordinal + 1} / 4",
                style = MaterialTheme.typography.labelMedium, color = colors.primary)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if(fraction != null) Text("${(fraction * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
            }
            val bar = Modifier.fillMaxWidth().testTag("favorite-sync-progress")
            if(fraction != null || appReducedMotion()) AppLinearProgressIndicator(progress = { fraction ?: 0f }, modifier = bar)
            else AppLinearProgressIndicator(modifier = bar)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable internal fun FavoriteSyncPreview(plan: FavoriteSyncPlan, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    AppLazyColumn(modifier, fillHeight = false,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp)) {
        item {
            Surface(shape = appRoundedCornerShape(18.dp), color = colors.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    SyncMetric("新增收藏", plan.additions, Modifier.weight(1f))
                    VerticalDivider(Modifier.height(36.dp), color = colors.outlineVariant)
                    SyncMetric("移动分组", plan.moves, Modifier.weight(1f))
                }
            }
            Text("待同步书目", Modifier.padding(top = 20.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall)
        }
        itemsIndexed(plan.changes, key = { _, change -> change.book.ref.key }) { index, change ->
            val adding = change.from == null
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(if(adding) Icons.Outlined.BookmarkAdd else Icons.AutoMirrored.Outlined.DriveFileMove,
                    null, Modifier.size(22.dp), tint = colors.primary)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(change.book.title, style = MaterialTheme.typography.titleSmall)
                    Text(change.from?.let { "$it → ${change.target.title}" } ?: "加入「${change.target.title}」",
                        style = MaterialTheme.typography.bodyMedium)
                    Text(listOfNotNull(if(adding) "新增" else "移动", if(change.book.ref.isWenku) "文库小说" else "网络小说",
                        "新建收藏夹".takeIf { change.target.create }).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
            }
            if(index != plan.changes.lastIndex) HorizontalDivider(color = colors.outlineVariant.copy(alpha = .5f))
        }
    }
}

@Composable private fun SyncMetric(label: String, count: Int, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(count.toString(), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
