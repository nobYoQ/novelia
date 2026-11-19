package cc.novelia.app.ui.book

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.library.mountedVolumes
import cc.novelia.app.data.library.renameLocalSeries
import cc.novelia.app.data.library.withMountedVolumes
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.files.downloads.deleteBookFiles
import cc.novelia.app.ui.components.base.AppButton
import cc.novelia.app.ui.components.base.AppDropdownMenu
import cc.novelia.app.ui.components.base.AppIconButton
import cc.novelia.app.ui.components.base.AppLazyColumn
import cc.novelia.app.ui.components.base.ConfirmDialog
import cc.novelia.app.ui.components.base.EmptyState
import cc.novelia.app.ui.components.base.Screen
import cc.novelia.app.ui.components.base.SectionTitle
import cc.novelia.app.ui.components.base.TextPrompt
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.shelf.MountedVolumeRow
import cc.novelia.app.ui.shelf.WenkuVolumeManager

@Composable internal fun LocalSeriesScreen(c: AppController, ref: BookRef, onBack: () -> Unit) {
    val state by c.store.state.collectAsStateWithLifecycle()
    val parent = remember(state.books, ref) { state.books.firstOrNull { it.book.ref == ref } }
    val volumes = remember(state.books, parent) { parent?.let { state.mountedVolumes(it) }.orEmpty() }
    var menu by remember(ref) { mutableStateOf(false) }
    var managing by rememberSaveable(ref.key) { mutableStateOf(false) }
    var renaming by rememberSaveable(ref.key) { mutableStateOf(false) }
    var dissolving by rememberSaveable(ref.key) { mutableStateOf(false) }
    val coverActions = rememberBookCoverActions(c, ref)
    Screen("本地总集", onBack, actions = {
        if(parent != null) Box {
            AppIconButton({ menu = true }) { Icon(Icons.Outlined.MoreVert, "更多操作") }
            AppDropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("重命名总集") }, { menu = false; renaming = true }, leadingIcon = { Icon(Icons.Outlined.Edit, null) })
                BookCoverMenuItems(coverActions) { menu = false }
                DropdownMenuItem({ Text("解散总集") }, { menu = false; dissolving = true },
                    enabled = !coverActions.busy, leadingIcon = { Icon(Icons.Outlined.RemoveCircleOutline, null) })
            }
        }
    }) { padding ->
        AppLazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp), listModifier = Modifier.testTag("local-series-detail")) {
            if(parent == null) item { EmptyState("总集已不在书架", "可以返回书架查看其他总集。") }
            else {
                item { BookHero(parent.book, "${volumes.size} 卷", state, coverActions.cover) }
                if(volumes.isEmpty()) item {
                    AppButton(onClick = { managing = true }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) { Text("添加分卷") }
                    EmptyState("还没有分卷", "可以把已导入的书籍添加到这个总集。")
                } else {
                    val latest = volumes.mapNotNull { volume -> state.positions[volume.book.ref.key]?.let { volume to it } }
                        .maxByOrNull { (_, position) -> position.updatedAt }
                    latest?.let { (volume, position) -> item {
                        AppButton(onClick = { c.read(volume.book.ref, position.chapterId) }, Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                            Text("继续阅读 · ${volume.book.title}", maxLines = 2)
                        }
                    } }
                    item { SectionTitle("分卷目录", "管理挂载") { managing = true } }
                }
                items(volumes, key = { it.book.ref.key }) { volume ->
                    val book = volume.book
                    MountedVolumeRow(volume, state.positions[book.ref.key], Modifier.padding(horizontal = 8.dp),
                        onClick = { c.book(book.ref) }, trailing = {
                            AppIconButton({ c.showBookDetails(book.ref) }) { Icon(Icons.Outlined.Info, "查看 ${book.title} 详情") }
                        }, indented = false)
                }
            }
        }
    }
    if(parent != null) {
        if(managing) WenkuVolumeManager(parent, state.books, { managing = false }) { keys ->
            c.action { c.store.update { it.withMountedVolumes(ref.key, keys) }; managing = false }
        }
        if(renaming) TextPrompt("重命名总集", "总集名称", parent.book.title, { renaming = false }) { name ->
            c.action { c.store.update { it.renameLocalSeries(ref, name) } }
        }
        if(dissolving) ConfirmDialog("解散总集？", "分卷将回到书架，保留文件、封面和阅读进度。", { dissolving = false }, "解散总集") {
            c.action("总集已解散") { deleteBookFiles(c.app, setOf(ref)); onBack() }
        }
    }
}
