package cc.novelia.app.ui.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.Profile
import cc.novelia.app.ui.components.AppLazyColumn
import cc.novelia.app.ui.theme.LocalEInkMode

/** 同一组真实入口在宽屏并列显示；空间不足或字号放大时恢复纵向阅读顺序。 */
@Composable internal fun ProfileDashboard(
    profile: Profile?,
    noteCount: Int,
    pendingCount: Int,
    onNavigate: (String) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val companionVisible by remember { derivedStateOf {
        listState.layoutInfo.visibleItemsInfo.any { it.key == "profile-card" }
    } }
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val wide = minOf(maxWidth, 1120.dp) / fontScale >= 840.dp
        AppLazyColumn(Modifier.widthIn(max = 1120.dp).fillMaxSize().testTag("profile-dashboard"), state = listState,
            listModifier = Modifier.testTag("profile-list"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(key = "profile-card") {
                ProfileAccountCard(profile, companionVisible, onLogin = { onNavigate("login") }, onLogout = onLogout)
            }
            if(wide) item(key = "profile-columns") {
                Row(Modifier.fillMaxWidth().testTag("profile-wide-layout"), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        ProfileReadingCards(onNavigate)
                        ProfileShortcuts(noteCount, onNavigate)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        ProfilePreferences(pendingCount, onNavigate)
                        ProfileAboutCard { onNavigate("about") }
                    }
                }
            } else {
                item(key = "profile-reading") { ProfileReadingCards(onNavigate) }
                item(key = "profile-shortcuts") { ProfileShortcuts(noteCount, onNavigate) }
                item(key = "profile-preferences") { ProfilePreferences(pendingCount, onNavigate) }
                item(key = "profile-about") { ProfileAboutCard { onNavigate("about") } }
            }
        }
    }
}

@Composable private fun ProfileReadingCards(onNavigate: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints {
        val stacked = maxWidth / fontScale < 300.dp
        val history: @Composable (Modifier) -> Unit = { modifier ->
            ProfileFeatureCard("阅读历史", "最近阅读记录", Icons.Outlined.History, "history", onNavigate,
                colors.primary, colors.onPrimary, RoundedCornerShape(topStart = 28.dp, topEnd = 48.dp, bottomEnd = 28.dp, bottomStart = 28.dp), modifier)
        }
        val downloads: @Composable (Modifier) -> Unit = { modifier ->
            ProfileFeatureCard("下载管理", "下载与离线内容", Icons.Outlined.Download, "downloads", onNavigate,
                colors.secondaryContainer, colors.onSecondaryContainer, RoundedCornerShape(28.dp), modifier)
        }
        if(stacked) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            history(Modifier.fillMaxWidth())
            downloads(Modifier.fillMaxWidth())
        } else Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            history(Modifier.weight(1.12f).fillMaxHeight())
            downloads(Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable private fun ProfileFeatureCard(
    title: String, subtitle: String, icon: ImageVector, route: String, onNavigate: (String) -> Unit,
    container: Color, content: Color, shape: Shape, modifier: Modifier,
) {
    Card(onClick = { onNavigate(route) }, modifier = modifier.testTag("profile-$route"), shape = shape,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content), border = profileCardBorder()) {
        Column(Modifier.fillMaxWidth().heightIn(min = 168.dp).padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(28.dp))
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(20.dp))
            }
            Spacer(Modifier.height(28.dp))
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun ProfileShortcuts(noteCount: Int, onNavigate: (String) -> Unit) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, border = profileCardBorder()) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(6.dp)) {
            val stacked = (maxWidth + 12.dp) / fontScale < 300.dp
            val shortcuts = listOf(
                Triple("书架更新", "updates", Icons.Outlined.NewReleases),
                Triple("书签与笔记", "notes", Icons.Outlined.EditNote),
                Triple("文件工具", "tools", Icons.Outlined.Handyman),
            )
            if(stacked) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                shortcuts.forEach { (title, route, icon) ->
                    ProfileShortcut(title, route, icon, if(route == "notes") "$noteCount 条" else null,
                        true, onNavigate, Modifier.fillMaxWidth())
                }
            } else Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                shortcuts.forEach { (title, route, icon) ->
                    ProfileShortcut(title, route, icon, if(route == "notes") "$noteCount 条" else null,
                        false, onNavigate, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable private fun ProfileShortcut(title: String, route: String, icon: ImageVector, detail: String?,
    stacked: Boolean, onNavigate: (String) -> Unit, modifier: Modifier) {
    Surface(onClick = { onNavigate(route) }, modifier = modifier.testTag("profile-$route"),
        shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        if(stacked) Row(Modifier.padding(14.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            if(detail != null) Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Column(Modifier.padding(horizontal = 4.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleSmall)
            if(detail != null) Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun ProfilePreferences(pendingCount: Int, onNavigate: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("偏好与数据", Modifier.padding(start = 4.dp, top = 4.dp), style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            ProfilePreferenceRow("设置", "阅读、外观与下载", Icons.Outlined.Tune, "settings", onNavigate,
                RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 6.dp, bottomEnd = 6.dp))
            ProfilePreferenceRow("屏蔽管理", "作品与标签", Icons.Outlined.Block, "blocked", onNavigate, RoundedCornerShape(6.dp))
            ProfilePreferenceRow("同步状态", if(pendingCount > 0) "$pendingCount 项待处理" else "自动同步与重试",
                Icons.Outlined.Sync, "sync", onNavigate, RoundedCornerShape(6.dp))
            ProfilePreferenceRow("阅读资料备份", "书架、进度与本地资料", Icons.Outlined.Backup, "backup", onNavigate,
                RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 24.dp, bottomEnd = 24.dp))
        }
    }
}

@Composable private fun ProfileAboutCard(onClick: () -> Unit) {
    ProfilePreferenceRow("帮助与关于", "使用说明、版本与反馈", Icons.Outlined.Info, "about", { onClick() }, RoundedCornerShape(24.dp))
}

@Composable private fun ProfilePreferenceRow(title: String, subtitle: String, icon: ImageVector, route: String,
    onNavigate: (String) -> Unit, shape: Shape) {
    Surface(onClick = { onNavigate(route) }, modifier = Modifier.fillMaxWidth().testTag("profile-$route"), shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow, border = profileCardBorder()) {
        Row(Modifier.heightIn(min = 80.dp).padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 电子纸上的相近底色用轮廓分隔，保持卡片边界可辨。 */
@Composable internal fun profileCardBorder(): BorderStroke? =
    if(LocalEInkMode.current) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null
