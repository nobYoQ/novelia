@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui.book

import cc.novelia.app.ui.components.base.AppListItem
import cc.novelia.app.ui.components.base.AppCheckbox
import cc.novelia.app.ui.components.base.AppTextButton
import cc.novelia.app.ui.components.base.AppOutlinedButton
import cc.novelia.app.ui.components.base.AppFilledTonalButton
import cc.novelia.app.ui.components.base.AppIconButton
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.WenkuDetail
import cc.novelia.app.data.library.siteVolumeIds
import cc.novelia.app.data.library.wenkuVolumeFiles
import cc.novelia.app.data.library.withMountedVolumes
import cc.novelia.app.data.model.JapaneseVolume
import cc.novelia.app.ui.shelf.WenkuVolumeManager
import cc.novelia.app.files.downloads.downloadedVolumeForReading
import cc.novelia.app.data.network.encodeSegment
import cc.novelia.app.ui.components.book.BookRow
import cc.novelia.app.ui.components.base.EmptyState
import cc.novelia.app.ui.components.base.MenuRow
import cc.novelia.app.ui.components.base.SectionTitle
import cc.novelia.app.ui.components.media.IllustrationViewer
import cc.novelia.app.ui.downloads.DownloadSheet
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.appReducedMotion

@Composable internal fun WenkuVolumesPanel(
    c: AppController, book: BookCard, detail: WenkuDetail?, uploadBusy: Boolean,
    onUpload: () -> Unit, onRefresh: () -> Unit, pickerFallback: @Composable () -> Unit = {},
) {
    val profile by c.session.profile.collectAsStateWithLifecycle()
    val library by c.store.state.collectAsStateWithLifecycle()
    val files = remember(library.books, library.downloadLinks, library.downloads, book.ref) { library.wenkuVolumeFiles(book.ref) }
    val parent = remember(library.books, book.ref) { library.books.firstOrNull { it.book.ref == book.ref } }
    val sources = rememberVolumeSources(library)
    var managing by rememberSaveable(book.ref.key) { mutableStateOf(false) }
    val translated = remember(detail) { detail?.volumeJp.orEmpty().associateBy { it.volumeId } }
    val account = profile?.username
    var selecting by rememberSaveable(book.ref.key, account) { mutableStateOf(false) }
    var selection by rememberSaveable(book.ref.key, account) { mutableStateOf(emptyList<String>()) }
    var download by remember(book.ref.key, account) { mutableStateOf(emptyList<String>()) }
    var opening by remember(book.ref.key) { mutableStateOf<String?>(null) }
    var expandedCover by remember(book.ref.key) { mutableStateOf<String?>(null) }
    fun openVolume(volumeId: String) {
        if(opening != null) return
        opening = volumeId
        c.action {
            try { c.book(downloadedVolumeForReading(c.app, book.ref, volumeId)) }
            finally { opening = null }
        }
    }
    val available = remember(translated) {
        translated.values.filter { it.total > 0 && maxOf(it.sakura, it.gpt, it.youdao) >= it.total }.map { it.volumeId }.distinct()
    }
    val selected = available.filter { it in selection }
    val reducedMotion = appReducedMotion()
    val orderedVolumes = remember(translated) {
        val ranks = siteVolumeIds(translated.values.map { "jp:${it.volumeId}" }).withIndex().associate { it.value to it.index }
        translated.values.sortedBy { ranks["jp:${it.volumeId}"] }
    }
    val volumeRow: @Composable LazyItemScope.(String, JapaneseVolume?) -> Unit = { id, volume ->
        WenkuFileRow(id, volume, id in available, id in files.downloadedIds, selecting, id in selected, opening != null,
            reducedMotion, onSelect = { checked -> selection = if(checked) selection + id else selection - id },
            onRead = { openVolume(id) }, onDownload = { download = listOf(id) })
    }
    LaunchedEffect(translated, available) {
        selection = selection.filter { it in available }
        if(translated.isEmpty()) selecting = false
    }
    BackHandler(selecting) { selecting = false; selection = emptyList() }
    val siteVolumes = remember(orderedVolumes, files.downloadedIds, selecting) {
        if(selecting) orderedVolumes else orderedVolumes.filterNot { it.volumeId in files.downloadedIds }
    }
    val localHeading = when {
        files.downloadedIds.isNotEmpty() -> "本书已下载"
        files.other.isNotEmpty() -> "其他挂载分卷"
        else -> null
    }
    val heading = localHeading ?: if(siteVolumes.isNotEmpty()) "原站译文分卷" else "分卷目录"
    Column {
        pickerFallback()
        if(selecting) {
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("已选 ${selected.size} 卷", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelLarge)
                AppTextButton({ selecting = false; selection = emptyList() }) { Text("取消") }
                val allSelected = available.isNotEmpty() && selected.size == available.size
                AppTextButton(onClick = { selection = if(allSelected) emptyList() else available }, enabled = available.isNotEmpty()) {
                    Text(if(allSelected) "取消全选" else "全选")
                }
                AppFilledTonalButton(onClick = { download = selected }, enabled = selected.isNotEmpty(), modifier = Modifier.testTag("wenku-batch-download")) {
                    Icon(Icons.Outlined.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("下载所选（${selected.size}）")
                }
            }
        }
        VolumeList(c, library, files.mounted, "wenku-volume-list", Modifier.weight(1f), showContinue = !selecting) {
            if(!selecting && (localHeading != null || parent != null || translated.isNotEmpty())) item("volume-heading") {
                FlowRow(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(heading, Modifier.align(Alignment.CenterVertically).padding(vertical = 8.dp),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row {
                        if(parent != null) AppTextButton({ managing = true }) { Text("管理挂载") }
                        if(translated.isNotEmpty()) AppTextButton({ selecting = true }, enabled = available.isNotEmpty()) {
                            Icon(Icons.Outlined.Checklist, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("多选")
                        }
                    }
                }
            }
            if(!selecting) {
                mountedVolumeItems(c, library, files.own, sourceLabel = { volume ->
                    files.sourceIds.getValue(volume.book.ref).filter { it != volume.book.title }.joinToString("、").ifBlank { null }
                }, action = { volume ->
                    val ids = files.sourceIds.getValue(volume.book.ref).filter { it in available }
                    if(ids.isNotEmpty()) AppIconButton({ download = ids }) { Icon(Icons.Outlined.Download, "重新下载分卷") }
                })
                items(files.unmountedDownloadIds.toList(), key = { "download-$it" }) { id -> volumeRow(id, translated[id]) }
                if(files.other.isNotEmpty() && files.downloadedIds.isNotEmpty()) item { SectionTitle("其他挂载分卷") }
                mountedVolumeItems(c, library, files.other, sourceLabel = { sources[it.book.ref] ?: "本地导入" })
            }
            if(siteVolumes.isNotEmpty() && (selecting || localHeading != null)) item { SectionTitle("原站译文分卷") }
            items(siteVolumes, key = { "jp-${it.volumeId}" }, contentType = { "translated-volume" }) { volume ->
                volumeRow(volume.volumeId, volume)
            }
            if(detail == null) {
                if(files.downloadedIds.isEmpty() && files.other.isEmpty()) item {
                    Text("本机暂无分卷，可通过“管理挂载”添加已导入的文件。", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
                }
                return@VolumeList
            }
            if(profile == null) item { EmptyState("登录后查看文库文件", "文库的资源目录与下载遵循原站权限。", action = "登录", onAction = { c.go("login") }) }
            if(detail.volumeZh.isNotEmpty() && profile?.role == "admin") item { SectionTitle("中文文件") }
            if(profile?.role == "admin") items(detail.volumeZh, key = { "zh-$it" }, contentType = { "chinese-volume" }) { name ->
                MenuRow(name, "打开原站提供的中文资源", Icons.Outlined.Description, { c.external("https://n.novelia.cc/files-wenku/${book.ref.id}/${encodeSegment(name)}") })
            }
            if(detail.volumes.isNotEmpty()) item { SectionTitle("出版卷目") }
            items(detail.volumes, key = { "published-${it.asin}" }, contentType = { "book" }) { volume ->
                BookRow(BookCard(book.ref, volume.titleZh ?: volume.title, volume.title, volume.cover, volume.publisher.orEmpty()), {
                    expandedCover = volume.coverHires?.takeIf(String::isNotBlank) ?: volume.cover?.takeIf(String::isNotBlank)
                },
                    modifier = if(reducedMotion) Modifier else Modifier.animateItem(fadeInSpec = tween(AppMotion.Release), placementSpec = tween(AppMotion.Standard), fadeOutSpec = tween(AppMotion.Exit)),
                    showReadingProgress = false, showBookMetadata = false, useCustomCover = false)
            }
            if(profile?.canEdit == true) item {
                AppOutlinedButton(onClick = onUpload, enabled = !uploadBusy, modifier = Modifier.fillMaxWidth().padding(20.dp)) { Text(if(uploadBusy) "正在上传…" else "上传日文分卷") }
            }
            if(files.downloadedIds.isEmpty() && files.other.isEmpty() && detail.volumes.isEmpty() && detail.volumeZh.isEmpty() && translated.isEmpty() && profile != null) item {
                EmptyState("暂时没有可用分卷", "可刷新资料，或在具有编辑权限时上传资源。", action = "刷新", onAction = onRefresh)
            }
        }
    }
    if(managing && parent != null) WenkuVolumeManager(parent, library.books, { managing = false }) { keys ->
        c.action { c.store.update { it.withMountedVolumes(parent.book.ref.key, keys) }; managing = false }
    }
    if(download.isNotEmpty()) DownloadSheet(c, book, download, onQueued = { selecting = false; selection = emptyList() }) { download = emptyList() }
    expandedCover?.let { IllustrationViewer(it, imageLabel = "封面") { expandedCover = null } }
}

@Composable private fun LazyItemScope.WenkuFileRow(
    id: String, volume: JapaneseVolume?, complete: Boolean, readable: Boolean,
    selecting: Boolean, checked: Boolean, opening: Boolean, reducedMotion: Boolean,
    onSelect: (Boolean) -> Unit, onRead: () -> Unit, onDownload: () -> Unit,
) {
    val motion = if(reducedMotion) Modifier else Modifier.animateItem(fadeInSpec = tween(AppMotion.Release), placementSpec = tween(AppMotion.Standard), fadeOutSpec = tween(AppMotion.Exit))
    AppListItem(
        headlineContent = { Text(id, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(listOfNotNull(volume?.let { "Sakura ${it.sakura} · GPT ${it.gpt} · 有道 ${it.youdao} / ${it.total}" },
            if(readable) "已下载，点击阅读" else if(!complete) "译文尚未完成" else null).joinToString(" · ")) },
        trailingContent = {
            if(selecting) AppCheckbox(checked, onCheckedChange = null, enabled = complete)
            else Row {
                if(readable) AppIconButton(onClick = onRead, enabled = !opening) {
                    Icon(Icons.AutoMirrored.Outlined.MenuBook, "阅读分卷")
                }
                if(volume != null) AppIconButton(onClick = onDownload, enabled = complete && !opening) { Icon(Icons.Outlined.Download, if(complete) "下载分卷" else "译文尚未完成") }
            }
        },
        modifier = motion.testTag("wenku-volume-$id").then(if(selecting) Modifier.toggleable(checked, enabled = complete, role = Role.Checkbox) { onSelect(it) } else Modifier.clickable(enabled = !opening && (readable || complete), onClickLabel = if(readable) "阅读分卷" else "下载分卷") {
            if(readable) onRead() else onDownload()
        }),
    )
}
