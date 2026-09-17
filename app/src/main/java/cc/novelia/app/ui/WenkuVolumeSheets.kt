@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.*

internal data class ShelfRowItem(val saved: SavedBook, val parent: SavedBook? = null, val volumeCount: Int = 0, val expanded: Boolean = false)

@Composable internal fun MountedVolumeRow(saved: SavedBook, position: Position?, modifier: Modifier, onClick: () -> Unit, trailing: @Composable () -> Unit, dragHandle: (@Composable () -> Unit)? = null) {
    val line = MaterialTheme.colorScheme.outlineVariant
    ListItem(headlineContent = { Text(saved.book.title, maxLines = 3) },
        supportingContent = { Text("${saved.status} · ${position?.title?.ifBlank { "继续阅读" } ?: "尚未开始阅读"}", maxLines = 2) },
        leadingContent = dragHandle ?: { Icon(Icons.AutoMirrored.Outlined.MenuBook, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = trailing,
        modifier = modifier.testTag("shelf-volume-${saved.book.ref.key}").padding(start = 28.dp, end = 8.dp)
            .drawBehind { drawLine(line, Offset.Zero, Offset(0f, size.height), strokeWidth = 1.dp.toPx()) }.motionClickable(onClick = onClick))
}

@Composable internal fun WenkuVolumeManager(parent: SavedBook, books: List<SavedBook>, onDismiss: () -> Unit, onSave: (Set<String>) -> Unit) {
    var query by rememberSaveable(parent.book.ref.key) { mutableStateOf("") }
    var chosen by rememberSaveable(parent.book.ref.key) { mutableStateOf(books.filter { it.book.ref.isLocal && it.parentWenkuKey == parent.book.ref.key }.map { it.book.ref.key }) }
    val localBooks = remember(books) { books.filter { it.book.ref.isLocal }.sortedBy { it.book.title } }
    val parents = remember(books) { books.filter { it.book.ref.isWenku }.associateBy { it.book.ref.key } }
    val filtered = remember(localBooks, query) { localBooks.filter { it.book.title.contains(query, true) } }
    AppSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(.85f).navigationBarsPadding().imePadding()) {
            AppLazyColumn(Modifier.weight(1f), listModifier = Modifier.testTag("wenku-volume-picker")) {
                item("heading") {
                    Text("管理挂载分卷", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
                    Text(parent.book.title, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                    Text("勾选已导入的分卷；取消勾选可解除挂载。选择其他文库下的分卷会更换其归属。", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
                item("search") {
                    OutlinedTextField(query, { query = it }, label = { Text("搜索本地分卷") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp))
                }
                if(localBooks.isEmpty()) item { Text("还没有可挂载的分卷，请先下载并导入阅读，或导入本地文件。", Modifier.padding(20.dp)) }
                else if(filtered.isEmpty()) item { Text("没有匹配的本地分卷", Modifier.padding(20.dp)) }
                items(filtered, key = { it.book.ref.key }) { volume ->
                    val key = volume.book.ref.key
                    ListItem(headlineContent = { Text(volume.book.title) },
                        supportingContent = { Text(parents[volume.parentWenkuKey]?.let { "已挂载：${it.book.title}" } ?: "未挂载") },
                        trailingContent = { Checkbox(key in chosen, onCheckedChange = null) },
                        modifier = Modifier.testTag("mount-volume-$key").toggleable(key in chosen, role = Role.Checkbox) { checked ->
                            chosen = if(checked) (chosen + key).distinct() else chosen - key
                        })
                }
            }
            Button(onClick = { onSave(chosen.toSet().intersect(localBooks.map { it.book.ref.key }.toSet())) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) { Text("保存挂载（${chosen.size}）") }
        }
    }
}

@Composable internal fun VolumeParentPicker(volume: SavedBook, books: List<SavedBook>, onDismiss: () -> Unit, onSelect: (String?) -> Unit) {
    var query by rememberSaveable(volume.book.ref.key) { mutableStateOf("") }
    val parents = remember(books, query) { books.filter { it.book.ref.isWenku && it.book.title.contains(query, true) }.sortedBy { it.book.title } }
    AppSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(.75f).navigationBarsPadding().imePadding()) {
            AppLazyColumn(Modifier.weight(1f), listModifier = Modifier.testTag("volume-parent-picker")) {
                item("heading") {
                    Text("挂载到文库小说", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
                    Text(volume.book.title, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                }
                item("search") {
                    OutlinedTextField(query, { query = it }, label = { Text("搜索文库收藏") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp))
                }
                if(volume.parentWenkuKey != null) item {
                    ListItem(headlineContent = { Text("取消挂载") }, supportingContent = { Text("保留分卷文件和阅读进度") }, modifier = Modifier.motionClickable { onSelect(null) })
                }
                if(parents.isEmpty()) item { Text(if(query.isBlank()) "请先把目标文库小说加入「我的收藏」。" else "没有匹配的文库收藏", Modifier.padding(20.dp)) }
                items(parents, key = { it.book.ref.key }) { parent ->
                    ListItem(headlineContent = { Text(parent.book.title) }, supportingContent = { Text(if(parent.book.ref.key == volume.parentWenkuKey) "当前所属文库" else parent.folder) },
                        modifier = Modifier.testTag("volume-parent-${parent.book.ref.key}").motionClickable { onSelect(parent.book.ref.key) })
                }
            }
        }
    }
}
