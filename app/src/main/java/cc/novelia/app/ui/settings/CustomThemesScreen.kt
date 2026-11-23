@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cc.novelia.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.model.ThemeBarGlass
import cc.novelia.app.data.model.ThemePreset
import cc.novelia.app.data.model.ThemePresets
import cc.novelia.app.data.model.MONOCHROME_APP_THEME
import cc.novelia.app.ui.theme.monochromeBorder
import cc.novelia.app.data.model.useAppPresets
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.ui.account.ProfileDetailList
import cc.novelia.app.ui.account.ProfileDetailScreen
import cc.novelia.app.ui.components.base.*
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.*
import java.util.UUID
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString

@Composable internal fun CustomThemesScreen(c: AppController) {
    val appearance by remember(c) { c.store.state.map { (it.theme == MONOCHROME_APP_THEME) to it.themePresets }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = remember(c) { c.store.state.value.let { (it.theme == MONOCHROME_APP_THEME) to it.themePresets } })
    val (monochrome, themes) = appearance
    var edit by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }

    fun startEdit(preset: ThemePreset) { edit = appJson.encodeToString(preset) }
    val initial = remember(edit) { edit?.let { appJson.decodeFromString<ThemePreset>(it) } }
    if (initial != null) {
        ThemeEditor(c, initial, isNew = themes.items.none { it.id == initial.id }) { edit = null }
        return
    }

    fun select(id: String?) = c.store.update { state ->
        if (id == null || state.themePresets.items.any { it.id == id })
            state.useAppPresets(state.themePresets.copy(activeId = id)) else state
    }
    val canAdd = themes.items.size < ThemePresets.MAX_PRESETS
    ProfileDetailScreen("自定义主题", c::back) { padding ->
        ProfileDetailList(Modifier.fillMaxSize().padding(padding)) {
            item {
                AppButton(onClick = {
                    val names = themes.items.mapTo(mutableSetOf()) { it.name }
                    val name = generateSequence(1) { it + 1 }.map { if (it == 1) "我的主题" else "我的主题 $it" }.first { it !in names }
                    val glass = ThemeBarGlass(enabled = true)
                    startEdit(ThemePreset(UUID.randomUUID().toString(), name, topBar = glass, bottomBar = glass))
                },
                    enabled = canAdd, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (canAdd) "新建主题" else "已保存 ${ThemePresets.MAX_PRESETS} 套主题")
                }
            }
            item {
                ThemeRow("默认外观", "内置主题", 0x3E6B3B, !monochrome && themes.activeId == null, { select(null) })
            }
            item {
                ThemeRow("黑白外观", "白底黑字 · 高对比描边", 0x000000, monochrome,
                    { c.store.update { it.copy(theme = MONOCHROME_APP_THEME) } })
            }
            items(themes.items, key = { it.id }) { preset ->
                var menu by remember { mutableStateOf(false) }
                ThemeRow(preset.name, if (preset.image == null) "纯色背景" else "图片背景", preset.accent,
                    !monochrome && themes.activeId == preset.id, { select(preset.id) }) {
                    Box {
                        AppIconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "管理${preset.name}") }
                        AppDropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("编辑") }, onClick = { menu = false; startEdit(preset) })
                            DropdownMenuItem(text = { Text("复制") }, enabled = canAdd, onClick = {
                                menu = false
                                startEdit(preset.copy(id = UUID.randomUUID().toString(),
                                    name = preset.name.take(ThemePreset.MAX_NAME - 3) + " 副本"))
                            })
                            DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; deleting = preset.id })
                        }
                    }
                }
            }
        }
    }
    themes.items.firstOrNull { it.id == deleting }?.let { preset ->
        val detail = if (!monochrome && themes.activeId == preset.id) "删除后将使用默认外观。" else "删除后无法撤销。"
        ConfirmDialog("删除「${preset.name}」？", detail, { deleting = null }, "删除") {
            c.action { c.store.customThemes.delete(preset.id) }
        }
    }
}

@Composable private fun ThemeRow(name: String, description: String, accent: Long, selected: Boolean,
    select: () -> Unit, actions: @Composable () -> Unit = {}) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = select, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = wallpaperCardColor(if (selected) scheme.secondaryContainer else scheme.surfaceContainerLow),
        contentColor = if (selected) scheme.onSecondaryContainer else scheme.onSurface,
        border = if (selected) BorderStroke(1.dp, scheme.primary) else monochromeBorder()) {
        Row(Modifier.heightIn(min = 72.dp).padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ColorSwatch(accent)
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
            if (selected) Icon(Icons.Outlined.Check, "正在使用")
            actions()
        }
    }
}

@Composable internal fun ColorSwatch(value: Long) {
    Surface(modifier = Modifier.size(32.dp), color = themeRgb(value), shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {}
}
