@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cc.novelia.app.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.launcher.LauncherIconManager
import cc.novelia.app.launcher.LauncherIconState
import cc.novelia.app.ui.components.AppLazyColumn
import cc.novelia.app.ui.components.AppSheet
import cc.novelia.app.ui.components.MenuRow

@Composable internal fun LauncherIconPreference(manager: LauncherIconManager) {
    val state by manager.state.collectAsStateWithLifecycle()
    var show by rememberSaveable { mutableStateOf(false) }
    val description = when {
        state.error != null -> state.error.orEmpty()
        !state.ready -> "正在读取图标…"
        state.pending -> "${state.selected?.title} · 待退到后台时切换"
        else -> state.selected?.title.orEmpty()
    }
    MenuRow("桌面图标", description, Icons.Outlined.Apps, { show = true })
    if (show) AppSheet(onDismissRequest = { show = false }) {
        LauncherIconPicker(state, manager::select, Modifier.fillMaxWidth().fillMaxHeight(.7f))
    }
}

@Composable internal fun LauncherIconPicker(state: LauncherIconState, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text("桌面图标", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.titleLarge)
        Text("选好后返回桌面，图标会在应用退到后台时切换。桌面刷新可能稍有延迟。",
            Modifier.padding(horizontal = 24.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium)
        if (state.pending) {
            Text("已选择${state.selected?.title.orEmpty()}，等待退到后台", Modifier.padding(horizontal = 24.dp, vertical = 8.dp).testTag("launcher-icon-pending"),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            state.current?.let { current ->
                TextButton(onClick = { onSelect(current.id) }, enabled = !state.saving, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text("取消待切换")
                }
            }
        }
        state.error?.let { Text(it, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error) }
        if (!state.ready && state.error == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(24.dp))
        AppLazyColumn(Modifier.weight(1f).selectableGroup(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(state.icons.filter { !it.hidden || it.id == state.selectedId || it.id in state.enabledIds }, key = { it.id }) { icon ->
                val selected = icon.id == state.selectedId
                Row(Modifier.fillMaxWidth().testTag("launcher-icon-${icon.id}")
                    .selectable(selected, enabled = !state.saving, role = Role.RadioButton, onClick = { onSelect(icon.id) })
                    .padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Image(painterResource(icon.drawable), contentDescription = null, Modifier.size(56.dp).clip(MaterialTheme.shapes.medium))
                    Column(Modifier.weight(1f)) {
                        Text(icon.title, style = MaterialTheme.typography.titleMedium)
                        if (icon.id in state.enabledIds) Text("当前使用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else if (selected) Text("待切换", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    RadioButton(selected, onClick = null, enabled = !state.saving)
                }
            }
        }
    }
}
