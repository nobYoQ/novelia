package cc.novelia.app.ui.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.catalog.NovelLocalFilter
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.ui.components.base.AppDropdownMenu
import cc.novelia.app.ui.components.base.CharacterCountFilterFields
import cc.novelia.app.ui.components.base.FilterOptions
import cc.novelia.app.ui.shelf.ShelfFilterSheet
import cc.novelia.app.ui.theme.appRoundedCornerShape
import cc.novelia.app.ui.theme.monochromeBorder
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
internal data class DiscoverFilters(
    val source: String = "",
    val type: Int = 0,
    val translate: Int = 0,
    val sort: Int = 0,
    val level: Int = 0,
    val local: NovelLocalFilter = NovelLocalFilter(),
)

private val draftSaver = Saver<DiscoverFilters, String>(
    save = { appJson.encodeToString(it) }, restore = { appJson.decodeFromString<DiscoverFilters>(it) },
)

@Composable
internal fun DiscoverFilterSheet(web: Boolean, current: DiscoverFilters, canEdit: Boolean,
    onDismiss: () -> Unit, onApply: (DiscoverFilters) -> Unit, onAssistant: () -> Unit, onSyntax: () -> Unit) {
    var draft by rememberSaveable(stateSaver = draftSaver) { mutableStateOf(current) }
    var valid by rememberSaveable { mutableStateOf(true) }
    var resetVersion by rememberSaveable { mutableIntStateOf(0) }
    ShelfFilterSheet("筛选作品", "discover", LocalConfiguration.current.screenHeightDp.dp * .9f, valid,
        onDismiss = onDismiss, onReset = { draft = DiscoverFilters(); resetVersion++; valid = true },
        onApply = { onApply(draft) }) {
        if(web) {
            WebSourceFilter(draft.source) { draft = draft.copy(source = it) }
            Surface(Modifier.fillMaxWidth().padding(vertical = 8.dp), shape = appRoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface, border = monochromeBorder()) {
                Column {
                    FilterChoice("连载状态", listOf("全部", "连载中", "已完结", "短篇"), draft.type) { draft = draft.copy(type = it) }
                    FilterChoice("已有译文", listOf("全部", "GPT", "Sakura"), draft.translate) { draft = draft.copy(translate = it) }
                    FilterChoice("排序", listOf("更新", "点击", "相关"), draft.sort) { draft = draft.copy(sort = it) }
                    if(canEdit) FilterChoice("分级", listOf("全部", "一般向", "R18"), draft.level.coerceIn(0, 2)) { draft = draft.copy(level = it) }
                }
            }
            key(resetVersion) {
                CharacterCountFilterFields(draft.local.characters, onValidity = { valid = it }) {
                    draft = draft.copy(local = draft.local.copy(characters = it))
                }
            }
        } else {
            val levels = listOf("全部小说", "轻小说", "轻文学", "文学", "非小说", "R18男性向", "R18女性向").take(if(canEdit) 7 else 5)
            FilterOptions("文库分类", levels, setOf(draft.level.coerceIn(levels.indices)), "discover-wenku-level", 3) {
                draft = draft.copy(level = it)
            }
        }
        DiscoverFilterHelp(onAssistant = onAssistant.takeIf { web }, onSyntax = onSyntax)
    }
}

@Composable private fun FilterChoice(label: String, options: List<String>, index: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { open = true }
        .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Box {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(options.getOrElse(index) { options.first() }, style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AppDropdownMenu(open, { open = false }) {
                options.forEachIndexed { optionIndex, option ->
                    DropdownMenuItem(text = { Text(option) }, modifier = Modifier.semantics { selected = optionIndex == index },
                        trailingIcon = if(optionIndex == index) ({ Icon(Icons.Outlined.Check, null) }) else null,
                        onClick = { open = false; onSelect(optionIndex) })
                }
            }
        }
    }
}
