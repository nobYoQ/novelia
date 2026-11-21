@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package cc.novelia.app.ui.reader

import cc.novelia.app.ui.theme.appControlShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.ReaderThemePalette
import cc.novelia.app.ui.components.base.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable internal fun ReaderThemeLibrary(current: ReaderThemePalette, onApply: (ReaderThemePalette) -> Unit,
    chooseOnly: Boolean = false) {
    val store = (LocalContext.current.applicationContext as NoveliaApplication).store
    val themes by remember(store) { store.state.map { it.readerThemePresets }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = remember(store) { store.state.value.readerThemePresets })
    var browsing by rememberSaveable { mutableStateOf(false) }
    var saveName by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteName by rememberSaveable { mutableStateOf<String?>(null) }

    if (chooseOnly && themes.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppOutlinedButton(onClick = { browsing = true }, enabled = themes.isNotEmpty()) {
            Text("已存主题（${themes.size}）")
        }
        if (!chooseOnly) AppTextButton(onClick = { saveName = "" }) { Text("保存当前主题") }
    }
    saveName?.let { input ->
        val name = input.trim()
        val existing = themes[name]
        AppAlertDialog(onDismissRequest = { saveName = null }, title = { Text("保存阅读主题") },
            text = {
                OutlinedTextField(value = input, onValueChange = { if (it.length <= ReaderThemePalette.MAX_NAME) saveName = it },
                    singleLine = true, label = { Text("主题名称") }, supportingText = {
                        Text(if (existing != null) "已有同名主题，覆盖后使用当前配色。" else "保存正文、背景、工具栏颜色和工具栏透明度。")
                    },
                    shape = appControlShape())
            }, confirmButton = {
                AppTextButton(enabled = name.isNotEmpty(), onClick = {
                    store.update { state ->
                        if (state.readerThemePresets[name] == existing)
                            state.copy(readerThemePresets = state.readerThemePresets + (name to current)) else state
                    }
                    if (store.state.value.readerThemePresets[name] == current) saveName = null
                }) { Text(if (existing == null) "保存" else "覆盖保存") }
            }, dismissButton = { AppTextButton(onClick = { saveName = null }) { Text("取消") } })
    }

    if (browsing) AppAlertDialog(onDismissRequest = { browsing = false }, title = { Text("已存阅读主题") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                items(themes.entries.toList(), key = { it.key }) { (name, palette) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(onClick = { onApply(palette); browsing = false }, modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.medium) {
                            Row(Modifier.heightIn(min = 52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Surface(shape = MaterialTheme.shapes.small, color = palette.colors.background.readerRgb()) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(44.dp)) {
                                        Text("文", color = palette.colors.text.readerRgb())
                                        Spacer(Modifier.fillMaxWidth().height(6.dp).background(
                                            palette.colors.toolbar.readerRgb().copy(alpha = 1f - palette.toolbarTransparency)))
                                    }
                                }
                                Column {
                                    Text(name, style = MaterialTheme.typography.bodyLarge)
                                    if (palette == current) Text("与当前配色一致", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        if (!chooseOnly) AppIconButton(onClick = { deleteName = name }) {
                            Icon(Icons.Outlined.DeleteOutline, "删除$name")
                        }
                    }
                }
            }
        }, confirmButton = { AppTextButton(onClick = { browsing = false }) { Text("关闭") } })

    deleteName?.let { name ->
        ConfirmDialog("删除「$name」？", "已应用到阅读器的配色会保留。", { deleteName = null }, "删除") {
            store.update { it.copy(readerThemePresets = it.readerThemePresets - name) }
            if (store.state.value.readerThemePresets.isEmpty()) browsing = false
        }
    }
}

private fun Long.readerRgb() = Color(0xFF000000L or this)
