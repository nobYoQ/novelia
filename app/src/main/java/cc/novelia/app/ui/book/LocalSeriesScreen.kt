package cc.novelia.app.ui.book

import cc.novelia.app.sharing.shareCard
import cc.novelia.app.ui.components.base.ContentShareButton

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.library.mountedVolumes
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.files.downloads.deleteBookFiles
import cc.novelia.app.ui.components.base.AppDropdownMenu
import cc.novelia.app.ui.components.base.AppIconButton
import cc.novelia.app.ui.components.base.ConfirmDialog
import cc.novelia.app.ui.components.base.EmptyState
import cc.novelia.app.ui.components.base.Screen
import cc.novelia.app.ui.components.base.SectionTitle
import cc.novelia.app.ui.components.base.AppTextButton
import cc.novelia.app.ui.components.book.rememberCustomBookCover
import cc.novelia.app.ui.components.media.MetaParagraph
import cc.novelia.app.ui.navigation.AppController

@Composable internal fun LocalSeriesScreen(c: AppController, ref: BookRef, onBack: () -> Unit) {
    val state by c.store.state.collectAsStateWithLifecycle()
    val keywordIndex by c.app.keywords.displayIndex.collectAsStateWithLifecycle()
    val parent = remember(state.books, ref) { state.books.firstOrNull { it.book.ref == ref } }
    val volumes = remember(state.books, parent) { parent?.let { state.mountedVolumes(it) }.orEmpty() }
    val sources = rememberVolumeSources(state)
    var menu by remember(ref) { mutableStateOf(false) }
    var dissolving by rememberSaveable(ref.key) { mutableStateOf(false) }
    val cover = rememberCustomBookCover(ref)
    Screen("本地总集", onBack, actions = {
        if(parent != null) {
            ContentShareButton(null, parent.book.shareCard(parent.seriesDescription, cover?.file), "分享作品", c::share)
            AppTextButton({ c.editLocalSeries(ref) }) { Text("编辑总集") }
            Box {
                AppIconButton({ menu = true }) { Icon(Icons.Outlined.MoreVert, "更多操作") }
                AppDropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("解散总集") }, { menu = false; dissolving = true },
                        leadingIcon = { Icon(Icons.Outlined.RemoveCircleOutline, null) })
                }
            }
        }
    }) { padding ->
        VolumeList(c, state, volumes, "local-series-detail", Modifier.padding(padding)) {
            if(parent == null) item { EmptyState("总集已不在书架", "可以返回书架查看其他总集。") }
            else {
                item {
                    val summary = listOf(parent.book.authors.joinToString("、"), "${volumes.size} 卷", parent.folder)
                        .filter(String::isNotBlank).joinToString(" · ")
                    BookHero(parent.book, summary, state, cover)
                }
                if(parent.book.tags.isNotEmpty()) item {
                    MetaParagraph("标签", parent.book.tags.joinToString(" · ") { keywordIndex.labels[it] ?: it })
                }
                if(parent.seriesDescription.isNotBlank()) item { MetaParagraph("简介", parent.seriesDescription) }
                item { SectionTitle("分卷目录", if(volumes.isEmpty()) "添加分卷" else "编辑分卷") { c.editLocalSeries(ref, startAtVolumes = true) } }
                mountedVolumeItems(c, state, volumes, sourceLabel = { sources[it.book.ref] ?: "本地导入" })
            }
        }
    }
    if(parent != null && dissolving) {
        ConfirmDialog("解散总集？", "分卷将回到书架，保留文件、封面和阅读进度。", { dissolving = false }, "解散总集") {
            c.action("总集已解散") { deleteBookFiles(c.app, setOf(ref)); onBack() }
        }
    }
}
