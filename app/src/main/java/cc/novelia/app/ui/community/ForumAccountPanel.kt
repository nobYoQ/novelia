package cc.novelia.app.ui.community

import cc.novelia.app.ui.components.AppScrollColumn
import cc.novelia.app.ui.components.AppDropdownMenu
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.appReducedMotion
import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import cc.novelia.app.data.model.ForumSort
import cc.novelia.app.data.model.Profile

internal enum class ForumAccountAction { LOGIN, POSTS, FAVORITES, LOCAL, STRIKES, LOGOUT }

@Composable internal fun ForumAccountMenu(profile: Profile?, onAction: (ForumAccountAction) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val reduced = appReducedMotion()
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = expanded
    val angle by animateFloatAsState(if(expanded) 180f else 0f, tween(if(reduced) 0 else AppMotion.Release), label = "forum account arrow")
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val position = remember(density) { ForumPanelPosition(with(density) { 12.dp.roundToPx() }, with(density) { 8.dp.roundToPx() }) }
    Box {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("forum-account-toggle").semantics {
            contentDescription = "论坛我的"
            stateDescription = if(expanded) "已展开" else "已收起"
        }) {
            Icon(Icons.Outlined.PersonOutline, null, Modifier.size(20.dp))
            Spacer(Modifier.width(4.dp)); Text("我的")
            Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(18.dp).graphicsLayer { rotationZ = angle })
        }
        if(visibility.currentState || visibility.targetState) {
            Popup(popupPositionProvider = position, onDismissRequest = { expanded = false }, properties = PopupProperties(focusable = true)) {
                AnimatedVisibility(visibility,
                    enter = if(reduced) EnterTransition.None else fadeIn(tween(AppMotion.Quick)) + scaleIn(tween(AppMotion.Standard), initialScale = .92f, transformOrigin = TransformOrigin(1f, 0f)),
                    exit = if(reduced) ExitTransition.None else fadeOut(tween(AppMotion.Exit)) + scaleOut(tween(AppMotion.Page), targetScale = .96f, transformOrigin = TransformOrigin(1f, 0f))) {
                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 4.dp, shadowElevation = 8.dp,
                        modifier = Modifier.width(minOf(336.dp, (configuration.screenWidthDp.dp - 24.dp).coerceAtLeast(1.dp)))
                            .heightIn(max = (configuration.screenHeightDp.dp - 32.dp).coerceAtLeast(1.dp)).testTag("forum-account-panel")) {
                        AppScrollColumn(contentModifier = Modifier.padding(vertical = 12.dp)) {
                            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
                                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.PersonOutline, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                                }
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(profile?.username ?: "我的论坛", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(profile?.role?.let { forumRoleLabel(it) } ?: "登录后查看个人记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { expanded = false }) { Icon(Icons.Outlined.Close, "收起我的论坛", Modifier.size(20.dp)) }
                            }
                            fun select(action: ForumAccountAction) { expanded = false; onAction(action) }
                            if(profile == null) FilledTonalButton(onClick = { select(ForumAccountAction.LOGIN) }, enabled = expanded, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { Text("登录论坛") }
                            ForumPanelItem("我的帖子", Icons.Outlined.Description, expanded) { select(ForumAccountAction.POSTS) }
                            ForumPanelItem("云端收藏", Icons.Outlined.BookmarkBorder, expanded) { select(ForumAccountAction.FAVORITES) }
                            ForumPanelItem("处罚记录", Icons.Outlined.Gavel, expanded) { select(ForumAccountAction.STRIKES) }
                            HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                            ForumPanelItem("本地收藏", Icons.Outlined.Bookmarks, expanded) { select(ForumAccountAction.LOCAL) }
                            if(profile != null) ForumPanelItem("退出论坛登录", Icons.Outlined.Logout, expanded, destructive = true) { select(ForumAccountAction.LOGOUT) }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun ForumPanelItem(label: String, icon: ImageVector, enabled: Boolean, destructive: Boolean = false, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = if(destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 52.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Icon(icon, null, Modifier.size(22.dp)); Spacer(Modifier.width(16.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp))
    }
}

private fun forumRoleLabel(role: String) = when(role) {
    "admin" -> "管理员"; "trusted" -> "可信成员"; "member" -> "普通成员"; "restricted" -> "受限账号"; "banned" -> "被封禁账号"; else -> role
}

internal class ForumPanelPosition(private val margin: Int, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = if(layoutDirection == LayoutDirection.Ltr) anchorBounds.right - popupContentSize.width else anchorBounds.left
        return IntOffset(x.coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)),
            (anchorBounds.bottom + gap).coerceIn(margin, (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)))
    }
}

@Composable internal fun ForumSortPicker(sort: ForumSort, onSelect: (ForumSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, modifier = Modifier.testTag("forum-sort")) {
            Text(sort.label); Icon(Icons.Outlined.KeyboardArrowDown, "选择帖子排序", Modifier.size(18.dp))
        }
        AppDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            ForumSort.entries.forEach { option -> DropdownMenuItem(
                text = { Text(option.label) },
                leadingIcon = { if(option == sort) Icon(Icons.Outlined.Check, "已选择") else Spacer(Modifier.size(24.dp)) },
                onClick = { expanded = false; if(sort != option) onSelect(option) }) }
        }
    }
}
