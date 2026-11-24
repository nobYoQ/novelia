@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui.book

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.library.WenkuVolumeFiles
import cc.novelia.app.data.library.withMountedVolumes
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.files.downloads.deleteMountedCopies
import cc.novelia.app.ui.components.base.*
import cc.novelia.app.ui.navigation.AppController

@Composable internal fun MountedVolumeSelection(
    c: AppController, book: BookCard, library: LibraryState, files: WenkuVolumeFiles,
    sources: Map<BookRef, String>, onDone: () -> Unit,
) {
    var selection by rememberSaveable(book.ref.key) { mutableStateOf(emptyList<String>()) }
    var deleting by remember { mutableStateOf<Set<BookRef>?>(null) }
    var removing by remember { mutableStateOf(false) }
    val transfer = rememberMountedVolumeTransfer(c, book)
    val busy = removing || transfer.busy
    val keys = remember(files.mounted) { files.mounted.map { it.book.ref.key }.toSet() }
    val selected = selection.intersect(keys)
    val chosen = (files.own + files.other).filter { it.book.ref.key in selected }
    val canAct = !busy && chosen.isNotEmpty()
    LaunchedEffect(keys, busy) {
        selection = selection.filter { it in keys }
        if(keys.isEmpty() && !busy) onDone()
    }
    BackHandler { if(!busy) onDone() }
    val toggle: (String) -> Unit = { key -> selection = if(key in selected) selection - key else selection + key }
    Column {
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val status = when {
                removing -> "正在删除分卷…"
                transfer.busy -> "正在处理分卷文件…"
                else -> "已选 ${chosen.size} 卷"
            }
            Text(status, Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.titleSmall)
            val all = keys.isNotEmpty() && selected == keys
            AppTextButton({ selection = if(all) emptyList() else keys.toList() }, enabled = !busy) { Text(if(all) "取消全选" else "全选") }
            AppTextButton(onDone, enabled = !busy) { Text("完成") }
        }
        VolumeList(c, library, files.mounted, "wenku-mounted-selection", Modifier.weight(1f), showContinue = false) {
            if(files.own.isNotEmpty()) item { SectionTitle("本书已下载") }
            mountedVolumeItems(c, library, files.own, sourceLabel = { volume ->
                files.sourceIds.getValue(volume.book.ref).filter { it != volume.book.title }.joinToString("、").ifBlank { null }
            }, selectedKeys = selected, onSelect = toggle, enabled = !busy)
            if(files.other.isNotEmpty()) item { SectionTitle("其他挂载分卷") }
            mountedVolumeItems(c, library, files.other, sourceLabel = { sources[it.book.ref] ?: "本地导入" },
                selectedKeys = selected, onSelect = toggle, enabled = !busy)
        }
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            AppTextButton({ transfer.start(chosen.map { it.book.ref }, false) }, enabled = canAct) {
                Text(if(chosen.size > 1) "导出 ZIP" else "导出文件")
            }
            AppTextButton({ transfer.start(chosen.map { it.book.ref }, true) }, enabled = canAct) {
                Text(if(chosen.size > 1) "分享 ZIP" else "分享文件")
            }
            AppTextButton({
                c.store.update { current ->
                    if(current.books.none { it.book.ref == book.ref }) current else {
                        val remaining = current.books.filter { it.parentWenkuKey == book.ref.key && it.book.ref.key !in selected }
                        current.withMountedVolumes(book.ref.key, remaining.map { it.book.ref.key }.toSet())
                    }
                }
                onDone()
                c.message("已解除挂载，分卷保留在本地书架")
            }, enabled = canAct) { Text("解除挂载") }
            AppTextButton({ deleting = chosen.map { it.book.ref }.toSet() }, enabled = canAct) { Text("删除本地副本") }
        }
    }
    deleting?.let { refs ->
        ConfirmDialog("删除 ${refs.size} 卷的本地副本？", "将删除所选副本及其对应的下载文件。导入前的原文件和阅读记录保留。此操作无法撤销。",
            { deleting = null }, "删除副本") {
            removing = true
            c.action {
                try {
                    val removed = deleteMountedCopies(c.app, book.ref, refs)
                    onDone()
                    c.message("已删除 ${removed} 卷的本地副本")
                } finally { removing = false }
            }
        }
    }
}
