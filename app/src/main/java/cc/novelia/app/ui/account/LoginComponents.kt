package cc.novelia.app.ui.account

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.network.BookSource
import cc.novelia.app.data.network.SourceSelection
import cc.novelia.app.ui.components.base.AppChipFlowRow
import cc.novelia.app.ui.components.base.AppScrollColumn
import cc.novelia.app.ui.components.base.FilterPanelExpandIcon
import cc.novelia.app.ui.feedback.StickerCompanion
import cc.novelia.app.ui.theme.appRoundedCornerShape

@Composable internal fun LoginPageLayout(
    register: Boolean,
    resetting: Boolean,
    forum: Boolean,
    modifier: Modifier = Modifier,
    source: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    var welcomeHeight by remember { mutableIntStateOf(0) }
    val companionVisible by remember { derivedStateOf { scroll.value < welcomeHeight } }
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
        val wide = minOf(maxWidth, 960.dp) / fontScale >= 840.dp
        val welcome: @Composable () -> Unit = {
            LoginWelcome(register, resetting, forum, companionVisible,
                Modifier.onSizeChanged { welcomeHeight = it.height })
        }
        AppScrollColumn(
            modifier = Modifier.widthIn(max = if(wide) 960.dp else 520.dp).fillMaxSize().testTag("login-page"),
            state = scroll,
            contentModifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if(wide) Row(Modifier.fillMaxWidth().testTag("login-wide-layout"),
                horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    welcome()
                    source()
                }
                Column(Modifier.weight(1.15f), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
            } else {
                welcome()
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
                source()
            }
        }
    }
}

@Composable private fun LoginWelcome(register: Boolean, resetting: Boolean, forum: Boolean,
    companionVisible: Boolean, modifier: Modifier = Modifier) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp).testTag("login-welcome")) {
        val text: @Composable (Modifier) -> Unit = { textModifier ->
            Column(textModifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if(forum) "社区账户" else "阅读账户", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Text(if(register) "注册账号" else if(resetting) "重置密码" else "登录账号",
                    style = MaterialTheme.typography.headlineMedium)
            }
        }
        if(maxWidth / fontScale < 280.dp) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StickerCompanion(Modifier.size(104.dp), visible = companionVisible)
            text(Modifier.fillMaxWidth())
        } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            text(Modifier.weight(1f))
            StickerCompanion(Modifier.size(112.dp), visible = companionVisible)
        }
    }
}

@Composable internal fun LoginField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    icon: ImageVector,
    tag: String,
    enabled: Boolean,
    first: Boolean = false,
    last: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    val shape = appRoundedCornerShape(topStart = if(first) 24.dp else 6.dp, topEnd = if(first) 24.dp else 6.dp,
        bottomStart = if(last) 24.dp else 6.dp, bottomEnd = if(last) 24.dp else 6.dp)
    val border = profileCardBorder()
    val colors = MaterialTheme.colorScheme
    TextField(value, onValueChange,
        modifier = Modifier.fillMaxWidth().testTag(tag).then(if(border != null) Modifier.border(border, shape) else Modifier),
        enabled = enabled, singleLine = true, label = { Text(label) }, shape = shape,
        leadingIcon = { Icon(icon, null, Modifier.size(22.dp)) }, trailingIcon = trailingIcon,
        visualTransformation = visualTransformation, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = colors.surfaceContainer, unfocusedContainerColor = colors.surfaceContainerLow,
            disabledContainerColor = colors.surfaceContainerLow,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            focusedLeadingIconColor = colors.primary, unfocusedLeadingIconColor = colors.onSurfaceVariant,
        ),
    )
}

@Composable internal fun LoginSourcePicker(selected: SourceSelection, forum: Boolean, enabled: Boolean,
    switching: Boolean, onSelect: (BookSource) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth(), shape = appRoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow, border = profileCardBorder()) {
        Column {
            Row(Modifier.fillMaxWidth().testTag("login-source-toggle")
                .semantics { stateDescription = if(expanded) "已展开" else "已收起" }
                .clickable(role = Role.Button, onClickLabel = if(expanded) "收起书源线路" else "展开书源线路") { expanded = !expanded }
                .heightIn(min = 76.dp).padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Icons.Outlined.Dns, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("书源线路", style = MaterialTheme.typography.titleSmall)
                    Text(if(switching) "正在切换…" else selected.source.title,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilterPanelExpandIcon(expanded)
            }
            if(expanded) Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AppChipFlowRow {
                    BookSource.entries.forEach { source ->
                        FilterChip(selected = selected.source == source,
                            enabled = enabled && (source == BookSource.ORIGINAL || selected.hasAccessToken),
                            modifier = Modifier.testTag("login-source-${source.id}"),
                            label = { Text(source.title) }, onClick = { onSelect(source) })
                    }
                }
                Text(selected.source.origin.substringAfter("://"), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("切换书源会同时切换小说和论坛线路。" + if(forum) "论坛登录状态单独保存。" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if(!selected.hasAccessToken) Text("当前安装包未包含镜像配置", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable internal fun LoginStatus(message: String, error: Boolean = false, loading: Boolean = false) {
    val color = if(error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if(loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else Icon(if(error) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle, null, Modifier.size(18.dp), tint = color)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}
