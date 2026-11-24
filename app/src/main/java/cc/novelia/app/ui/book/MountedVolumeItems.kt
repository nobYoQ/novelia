package cc.novelia.app.ui.book

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.library.mountedVolumes
import cc.novelia.app.data.library.withVolumeOrder
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.ui.components.base.AppDropdownMenu
import cc.novelia.app.ui.components.base.AppIconButton
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.shelf.MountedVolumeRow
import cc.novelia.app.ui.theme.LocalEInkMode

@Composable internal fun rememberVolumeSources(library: LibraryState): Map<BookRef, String> =
    remember(library.downloadLinks, library.books) {
        val titles = library.books.associate { it.book.ref to it.book.title }
        library.downloadLinks.mapNotNull { link -> link.sourceBook?.let { link.localBook to it } }
            .distinct().groupBy({ it.first }, { it.second }).mapValues { (_, refs) ->
                refs.joinToString("、") { source -> titles[source]?.let { "下载自《$it》" }
                    ?: if(source.isWenku) "文库下载" else "网络小说下载" }
            }
    }

internal fun LazyListScope.mountedVolumeItems(
    c: AppController, library: LibraryState, volumes: List<SavedBook>,
    sourceLabel: (SavedBook) -> String?, action: (@Composable (SavedBook) -> Unit)? = null,
) {
    items(volumes.size, key = { volumes[it].book.ref.key }, contentType = { "mounted-volume" }) { index ->
        val volume = volumes[index]
        val ref = volume.book.ref
        val buttons = LocalEInkMode.current && volumes.size > 1
        var menu by remember { mutableStateOf(false) }
        fun move(delta: Int) {
            menu = false
            val neighbor = volumes.getOrNull(index + delta)?.book?.ref ?: return
            c.store.update { current ->
                val parent = current.books.firstOrNull { it.book.ref.key == volume.parentWenkuKey } ?: return@update current
                val keys = current.mountedVolumes(parent).map { it.book.ref.key }.toMutableList()
                val from = keys.indexOf(ref.key)
                val to = keys.indexOf(neighbor.key)
                if(from < 0 || to < 0) return@update current
                keys[from] = neighbor.key
                keys[to] = ref.key
                current.withVolumeOrder(parent.book.ref.key, keys)
            }
        }
        Box {
            MountedVolumeRow(volume, library.positions[ref.key], onClick = { c.book(ref) },
                onLongClick = if(!buttons && volumes.size > 1) ({ menu = true }) else null,
                sourceLabel = sourceLabel(volume), trailing = {
                    if(action != null) action(volume) else AppIconButton({ c.showBookDetails(ref) }) {
                        Icon(Icons.Outlined.Info, "查看 ${volume.book.title} 详情")
                    }
                }, orderButtons = if(buttons) ({
                    Column {
                        AppIconButton({ move(-1) }, enabled = index > 0, modifier = Modifier.size(48.dp).testTag("volume-up-${ref.key}")) {
                            Icon(Icons.Outlined.KeyboardArrowUp, "上移 ${volume.book.title}")
                        }
                        AppIconButton({ move(1) }, enabled = index < volumes.lastIndex, modifier = Modifier.size(48.dp).testTag("volume-down-${ref.key}")) {
                            Icon(Icons.Outlined.KeyboardArrowDown, "下移 ${volume.book.title}")
                        }
                    }
                }) else null)
            if(menu) AppDropdownMenu(true, { menu = false }) {
                DropdownMenuItem({ Text("上移一位") }, { move(-1) }, enabled = index > 0)
                DropdownMenuItem({ Text("下移一位") }, { move(1) }, enabled = index < volumes.lastIndex)
            }
        }
    }
}
