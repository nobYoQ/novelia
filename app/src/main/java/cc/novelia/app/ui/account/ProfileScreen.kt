@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cc.novelia.app.ui.account

import cc.novelia.app.ui.components.AppTextButton
import cc.novelia.app.ui.components.AppFilledTonalButton
import cc.novelia.app.ui.components.AppIconButton

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import cc.novelia.app.ui.theme.appRoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.model.Profile
import cc.novelia.app.ui.components.AppDropdownMenu
import cc.novelia.app.ui.components.AppScrollColumn
import cc.novelia.app.ui.components.ConfirmDialog
import cc.novelia.app.ui.components.Screen
import cc.novelia.app.ui.components.displayDate
import cc.novelia.app.ui.components.FilterPanelExpandIcon
import cc.novelia.app.ui.components.StaticMenuPosition
import cc.novelia.app.ui.feedback.MidoriCompanion
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.LocalEInkMode
import cc.novelia.app.ui.theme.appReducedMotion

@Composable fun ProfileScreen(c: AppController) {
    val profile by c.session.profile.collectAsStateWithLifecycle()
    val state by c.store.state.collectAsStateWithLifecycle()
    var logout by remember { mutableStateOf(false) }
    Screen("我的", actions = {
        AppIconButton(onClick = { c.go("settings") }) { Icon(Icons.Outlined.Tune, "设置") }
    }) { padding ->
        ProfileDashboard(profile, state.notes.size, state.pending.count { it.account == profile?.username },
            onNavigate = { c.go(it) }, onLogout = { logout = true }, modifier = Modifier.padding(padding))
    }
    if(logout) ConfirmDialog("退出主站登录？", "本地小说、下载和笔记仍保留在此设备。", { logout = false }, confirmLabel = "退出主站登录") { c.action("已退出主站登录") { c.session.logout() } }
}

@Composable internal fun ProfileAccountCard(profile: Profile?, companionVisible: Boolean,
    onLogin: () -> Unit, onLogout: () -> Unit, modifier: Modifier = Modifier) {
    var accountMenu by remember(profile?.username) { mutableStateOf(false) }
    val separateLogin = LocalDensity.current.fontScale > 1.3f
    Surface(modifier.fillMaxWidth().testTag("profile-account-card"), shape = appRoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow, border = profileCardBorder()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val role = profile?.let { mapOf("admin" to "管理员", "member" to "普通成员", "trusted" to "可信成员",
                        "restricted" to "受限账号", "banned" to "被封禁账号")[it.role] ?: it.role }
                    Text(role ?: "阅读账户", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(profile?.username ?: "未登录", style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if(profile != null) Text("注册于 ${displayDate(profile.createdAt)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else if(!separateLogin) AppTextButton(onClick = onLogin, contentPadding = PaddingValues(vertical = 8.dp),
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("登录 / 注册") }
                }
                Column(horizontalAlignment = Alignment.End) {
                    if(profile != null) Box {
                        AppIconButton(onClick = { accountMenu = true }, modifier = Modifier.size(48.dp).testTag("profile-account-menu")) {
                            Icon(Icons.Outlined.MoreHoriz, "账号操作")
                        }
                        AppDropdownMenu(accountMenu, { accountMenu = false }) {
                            DropdownMenuItem(text = { Text("退出登录") }, onClick = { accountMenu = false; onLogout() },
                                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Logout, null) },
                                modifier = Modifier.heightIn(min = 48.dp).testTag("profile-logout"))
                        }
                    }
                    MidoriCompanion(Modifier.size(96.dp), visible = companionVisible)
                }
            }
            if(profile == null && separateLogin) AppFilledTonalButton(onClick = onLogin,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("登录 / 注册") }
            if(profile != null) AccountPermissionsCard(profile.username, profile.canPost, profile.canEdit, Modifier.widthIn(max = 520.dp))
        }
    }
}

@Composable internal fun AccountPermissionsCard(username: String, canPost: Boolean, canEdit: Boolean, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(username) { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        AppTextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()
            .heightIn(min = 48.dp).testTag("account-permissions-toggle")
            .semantics { stateDescription = if(expanded) "已展开" else "已收起" },
            colors = ButtonDefaults.textButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            shape = appRoundedCornerShape(20.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
            Icon(Icons.Outlined.VerifiedUser, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("账号权限", Modifier.weight(1f))
            Spacer(Modifier.width(4.dp))
            FilterPanelExpandIcon(expanded)
        }
        key(username) {
            AccountPermissionsPopup(expanded, canPost, canEdit, onDismiss = { expanded = false })
        }
    }
}

/** 与论坛“我的”一致的浮层层级；Popup 不参与账号卡片和下方入口的测量。 */
@Composable private fun AccountPermissionsPopup(expanded: Boolean, canPost: Boolean, canEdit: Boolean, onDismiss: () -> Unit) {
    // 浮层刚打开、窗口焦点尚未切换时，返回键也只收起浮层。
    BackHandler(enabled = expanded, onBack = onDismiss)
    val reduced = appReducedMotion()
    val visibility = remember { MutableTransitionState(false) }
    visibility.targetState = expanded
    val margin = with(LocalDensity.current) { 12.dp.roundToPx() }
    val position = remember(margin) { StaticMenuPosition(margin) }
    val configuration = LocalConfiguration.current
    if(visibility.currentState || visibility.targetState) {
        Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
            AnimatedVisibility(visibility,
                enter = if(reduced) EnterTransition.None else fadeIn(tween(AppMotion.Quick)) +
                    scaleIn(tween(AppMotion.Standard), initialScale = .92f, transformOrigin = TransformOrigin(0f, 0f)),
                exit = if(reduced) ExitTransition.None else fadeOut(tween(AppMotion.Exit)) +
                    scaleOut(tween(AppMotion.Page), targetScale = .96f, transformOrigin = TransformOrigin(0f, 0f))) {
                Surface(shape = appRoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 4.dp, shadowElevation = if(LocalEInkMode.current) 0.dp else 8.dp, border = profileCardBorder(),
                    modifier = Modifier.width(minOf(336.dp, (configuration.screenWidthDp.dp - 24.dp).coerceAtLeast(1.dp)))
                        .heightIn(max = (configuration.screenHeightDp.dp - 32.dp).coerceAtLeast(1.dp))
                        .testTag("account-permissions-panel").semantics { paneTitle = "账号权限" }) {
                    AppScrollColumn(contentModifier = Modifier.padding(vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("账号权限", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            AppIconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Outlined.Close, "收起账号权限", Modifier.size(20.dp))
                            }
                        }
                        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            PermissionStatus("社区发布", canPost)
                            PermissionStatus("书籍编辑", canEdit)
                        }
                    }
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
