package cc.novelia.app.ui.discover

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.catalog.NovelLocalFilter
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.ui.components.base.PanelChoiceRow
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
                    PanelChoiceRow("连载状态", listOf("全部", "连载中", "已完结", "短篇"), draft.type) { draft = draft.copy(type = it) }
                    PanelChoiceRow("已有译文", listOf("全部", "GPT", "Sakura"), draft.translate) { draft = draft.copy(translate = it) }
                    PanelChoiceRow("排序", listOf("更新", "点击", "相关"), draft.sort) { draft = draft.copy(sort = it) }
                    if(canEdit) PanelChoiceRow("分级", listOf("全部", "一般向", "R18"), draft.level.coerceIn(0, 2)) { draft = draft.copy(level = it) }
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
