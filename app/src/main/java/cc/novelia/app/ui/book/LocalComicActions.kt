package cc.novelia.app.ui.book

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.model.*
import cc.novelia.app.files.comic.importComicSeries
import cc.novelia.app.files.comic.seriesTree
import cc.novelia.app.ui.navigation.AppController
import kotlinx.coroutines.*

@Composable internal fun EpubMediumChoice(c: AppController, ref: BookRef, doc: LocalDocument) {
    val state by c.store.state.collectAsStateWithLifecycle()
    val saved = state.books.firstOrNull { it.book.ref == ref }
    val automatic by produceState<BookMedium?>(null, doc) {
        value = withContext(Dispatchers.IO) { doc.bookMedium { c.store.documentChapter(ref.id, it) } }
    }
    var open by remember { mutableStateOf(false) }
    fun label(value: BookMedium?) = when (value) { BookMedium.Comic -> "作为漫画阅读"; BookMedium.Novel -> "作为小说阅读"; null -> "自动识别" }
    Box(Modifier.padding(horizontal = 12.dp)) {
        TextButton(onClick = { open = true }, enabled = automatic != null) { Text("阅读方式 · " + label(saved?.epubMediumOverride)) }
        DropdownMenu(open, { open = false }) {
            listOf(null, BookMedium.Comic, BookMedium.Novel).forEach { choice ->
                DropdownMenuItem(text = { Text(label(choice)) }, onClick = {
                    open = false
                    if (saved == null) c.store.saveBook(BookCard(ref, doc.name, cover = doc.coverImage?.let { c.store.documentImage(ref.id, it).absolutePath }))
                    c.store.update { current ->
                        val next = choice ?: automatic
                        val old = current.books.firstOrNull { it.book.ref == ref }
                        val position = current.positions[ref.key]
                        val positions = if (position != null && old?.book?.shelfMedium != next) {
                            val index = if (next == BookMedium.Comic) position.sourceParagraph?.plus(1) ?: position.index else position.index
                            current.positions + (ref.key to position.copy(index = index, offset = 0, textOffset = 0))
                        } else current.positions
                        current.copy(positions = positions, books = current.books.map { item ->
                            if (item.book.ref == ref) item.copy(epubMediumOverride = choice, book = item.book.copy(medium = next)) else item
                        })
                    }
                })
            }
        }
    }
}

@Composable internal fun SeriesRefresh(c: AppController, ref: BookRef, reload: () -> Unit) {
    val scope = rememberCoroutineScope()
    var working by remember(ref) { mutableStateOf(false) }
    var message by remember(ref) { mutableStateOf("") }
    fun refresh(tree: Uri) {
        if (working) return
        working = true
        scope.launch {
            try {
                val result = importComicSeries(c.store, tree, ref.id)
                message = if (result.imported) "已加入新话" else "已经是最新目录"
                reload()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.localizedMessage ?: "读取失败，请重新选择目录" }
            finally { working = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { c.store.context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            refresh(uri)
        }
    }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row {
            TextButton(enabled = !working, onClick = { c.store.seriesTree(ref.id)?.let(::refresh) ?: picker.launch(null) }) {
                Text(if (working) "正在检查新话…" else "刷新系列目录")
            }
            TextButton(enabled = !working, onClick = { picker.launch(null) }) { Text("重新选择目录") }
        }
        if (message.isNotBlank()) Text(message)
    }
}
