@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cc.novelia.app.ui.settings

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.network.BookSource
import cc.novelia.app.ui.components.AppSheet
import cc.novelia.app.ui.components.MenuRow
import cc.novelia.app.ui.navigation.AppController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun BookSourcePreference(c: AppController) {
    val selected by c.app.bookSources.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    MenuRow("书源线路", "${selected.source.title} · ${selected.source.origin.substringAfter("://")}", Icons.Outlined.Public, { open = true })
    if(open) BookSourcePicker(selected.source, selected.hasAccessToken, busy, { open = false }) { source ->
        if(source == selected.source) { open = false; return@BookSourcePicker }
        busy = true
        c.action {
            try {
                withContext(Dispatchers.IO) { c.app.bookSources.select(source) }
                open = false
                c.message("已切换至${source.title}")
                if(c.session.profile.value == null) c.go("login")
            } finally { busy = false }
        }
    }
}

@Composable internal fun BookSourcePicker(selected: BookSource, mirrorAvailable: Boolean, busy: Boolean,
    onDismiss: () -> Unit, onSelect: (BookSource) -> Unit,
) {
    AppSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text("书源线路", style = MaterialTheme.typography.titleLarge)
            Text("小说内容相同，可按连接情况切换。首次使用另一条线路需要重新登录。", Modifier.padding(vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BookSource.entries.forEach { source ->
                val enabled = !busy && (source == BookSource.ORIGINAL || mirrorAvailable)
                Row(Modifier.fillMaxWidth().testTag("book-source-${source.id}")
                    .selectable(selected = source == selected, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(source) }).padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(source == selected, onClick = null, enabled = enabled)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(source.title, style = MaterialTheme.typography.titleMedium)
                        Text(source.origin.substringAfter("://"), style = MaterialTheme.typography.bodySmall)
                        if(source == BookSource.XKVI) Text(if(mirrorAvailable) "白金大佬提供的反代线路" else "当前安装包未包含镜像配置",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if(source == selected) Text("使用中", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
