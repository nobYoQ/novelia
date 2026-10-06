@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui.book

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.WenkuDetail
import cc.novelia.app.data.library.siteVolumeIds
import cc.novelia.app.data.network.encodeSegment
import cc.novelia.app.ui.components.AppLazyColumn
import cc.novelia.app.ui.components.BookRow
import cc.novelia.app.ui.components.EmptyState
import cc.novelia.app.ui.components.MenuRow
import cc.novelia.app.ui.components.SectionTitle
import cc.novelia.app.ui.downloads.DownloadSheet
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.appReducedMotion

@Composable internal fun WenkuVolumesPanel(
    c: AppController, book: BookCard, detail: WenkuDetail, uploadBusy: Boolean,
    onUpload: () -> Unit, onRefresh: () -> Unit,
) {
    val profile by c.session.profile.collectAsStateWithLifecycle()
    val account = profile?.username
    var selecting by rememberSaveable(book.ref.key, account) { mutableStateOf(false) }
    var selection by rememberSaveable(book.ref.key, account) { mutableStateOf(emptyList<String>()) }
    var download by remember(book.ref.key, account) { mutableStateOf(emptyList<String>()) }
    val available = remember(detail.volumeJp) {
        detail.volumeJp.filter { it.total > 0 && maxOf(it.sakura, it.gpt, it.youdao) >= it.total }.map { it.volumeId }.distinct()
    }
    val selected = available.filter { it in selection }
    val reducedMotion = appReducedMotion()
    val orderedVolumes = remember(detail.volumeJp) {
        val ranks = siteVolumeIds(detail.volumeJp.map { "jp:${it.volumeId}" }).withIndex().associate { it.value to it.index }
        detail.volumeJp.sortedBy { ranks["jp:${it.volumeId}"] }
    }
    LaunchedEffect(available) { selection = selection.filter { it in available } }
    BackHandler(selecting) { selecting = false; selection = emptyList() }
    Column {
        if(detail.volumeJp.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("已有译文的分卷", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { selecting = !selecting; selection = emptyList() }, enabled = selecting || available.isNotEmpty()) {
                    Icon(if(selecting) Icons.Outlined.Close else Icons.Outlined.Checklist, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if(selecting) "取消" else "多选")
                }
            }
            if(selecting) {
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("已选 ${selected.size} 卷", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelLarge)
                    val allSelected = available.isNotEmpty() && selected.size == available.size
                    TextButton(onClick = { selection = if(allSelected) emptyList() else available }, enabled = available.isNotEmpty()) {
                        Text(if(allSelected) "取消全选" else "全选")
                    }
                    FilledTonalButton(onClick = { download = selected }, enabled = selected.isNotEmpty(), modifier = Modifier.testTag("wenku-batch-download")) {
                        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("下载所选（${selected.size}）")
                    }
                }
            }
        }
        AppLazyColumn(Modifier.weight(1f).testTag("wenku-volume-list"), contentPadding = PaddingValues(bottom = 24.dp)) {
            if(profile == null) item { EmptyState("登录后查看文库文件", "文库的资源目录与下载遵循原站权限。", action = "登录", onAction = { c.go("login") }) }
            items(orderedVolumes, key = { "jp-${it.volumeId}" }, contentType = { "translated-volume" }) { volume ->
                val complete = volume.volumeId in available
                val checked = volume.volumeId in selected
                val motion = if(reducedMotion) Modifier else Modifier.animateItem(fadeInSpec = tween(AppMotion.Release), placementSpec = tween(AppMotion.Standard), fadeOutSpec = tween(AppMotion.Exit))
                ListItem(
                    headlineContent = { Text(volume.volumeId) },
                    supportingContent = { Text("Sakura ${volume.sakura} · GPT ${volume.gpt} · 有道 ${volume.youdao} / ${volume.total}${if(!complete) " · 译文尚未完成" else ""}") },
                    trailingContent = {
                        if(selecting) Checkbox(checked, onCheckedChange = null, enabled = complete)
                        else IconButton(onClick = { download = listOf(volume.volumeId) }, enabled = complete) { Icon(Icons.Outlined.Download, if(complete) "下载分卷" else "译文尚未完成") }
                    },
                    modifier = motion.testTag("wenku-volume-${volume.volumeId}").then(if(selecting) Modifier.toggleable(checked, enabled = complete, role = Role.Checkbox) { chosen ->
                        selection = if(chosen) selection + volume.volumeId else selection - volume.volumeId
                    } else Modifier),
                )
            }
            if(detail.volumeZh.isNotEmpty()) item { SectionTitle("中文文件") }
            if(profile?.role == "admin") items(detail.volumeZh, key = { "zh-$it" }, contentType = { "chinese-volume" }) { name ->
                MenuRow(name, "打开原站提供的中文资源", Icons.Outlined.Description, { c.external("https://n.novelia.cc/files-wenku/${book.ref.id}/${encodeSegment(name)}") })
            }
            if(detail.volumes.isNotEmpty()) item { SectionTitle("出版卷目") }
            items(detail.volumes, key = { "published-${it.asin}" }, contentType = { "book" }) { volume ->
                BookRow(BookCard(book.ref, volume.titleZh ?: volume.title, volume.title, volume.cover, volume.publisher.orEmpty()), { volume.coverHires?.let(c::external) },
                    modifier = if(reducedMotion) Modifier else Modifier.animateItem(fadeInSpec = tween(AppMotion.Release), placementSpec = tween(AppMotion.Standard), fadeOutSpec = tween(AppMotion.Exit)),
                    showReadingProgress = false, showBookMetadata = false)
            }
            if(profile?.canEdit == true) item {
                OutlinedButton(onClick = onUpload, enabled = !uploadBusy, modifier = Modifier.fillMaxWidth().padding(20.dp)) { Text(if(uploadBusy) "正在上传…" else "上传日文分卷") }
            }
            if(detail.volumes.isEmpty() && detail.volumeJp.isEmpty() && profile != null) item {
                EmptyState("暂时没有可用分卷", "可刷新资料，或在具有编辑权限时上传资源。", action = "刷新", onAction = onRefresh)
            }
        }
    }
    if(download.isNotEmpty()) DownloadSheet(c, book, download, onQueued = { selecting = false; selection = emptyList() }) { download = emptyList() }
}
