package cc.novelia.app.ui.reader.comic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.files.comic.encodeComicPageCover
import cc.novelia.app.ui.components.base.AppAlertDialog
import cc.novelia.app.ui.components.base.AppOutlinedButton
import cc.novelia.app.ui.components.base.AppTextButton
import cc.novelia.app.ui.components.book.rememberCustomBookCover
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.appBookCoverShape
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun ComicCoverDialog(c: AppController, ref: BookRef, book: LocalComicVolume, page: Int,
    series: BookCard?, dismiss: () -> Unit,
) {
    val source = book.pages[page]
    val chapter = book.sections[book.sections.sectionIndexAt(page)]
    val chapterId = chapter.id.takeIf { book.hasChapterCovers }
    val chapterCover = if (chapterId != null) rememberCustomBookCover(ref, chapterId) else null
    var working by remember(ref, page) { mutableStateOf(false) }
    fun applyCover(target: BookRef, sectionId: String? = null) {
        working = true
        c.action("封面已更新") {
            try {
                val image = encodeComicPageCover(source)
                c.store.saveComicCover(ref, target, sectionId) { image.inputStream() }
                dismiss()
            } finally { working = false }
        }
    }
    AppAlertDialog(onDismissRequest = { if (!working) dismiss() }, title = { Text("用本页设置封面") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${chapter.title} · 第 ${page - chapter.first + 1} 页")
            AppOutlinedButton({ applyCover(ref, chapterId) }, Modifier.fillMaxWidth(), enabled = !working) { Text("设为本话封面") }
            if (book.hasChapterCovers) AppOutlinedButton({ applyCover(ref) }, Modifier.fillMaxWidth(), enabled = !working) {
                Text(if (book.archiveSeries && series == null) "设为总集封面" else "设为本册封面")
            }
            if (series != null) AppOutlinedButton({ applyCover(series.ref) }, Modifier.fillMaxWidth(), enabled = !working) {
                Text("设为总集封面 · ${series.title}")
            }
            if (chapterId != null && chapterCover != null) AppTextButton(enabled = !working, onClick = {
                working = true
                c.action("已恢复本话的默认封面") {
                    try {
                        withContext(Dispatchers.IO) {
                            check(c.store.recoveryIssue.value == null) { "本地资料已保护，请先前往资料备份与恢复" }
                            c.store.bookCovers.restoreDefault(ref, chapterId)
                        }
                        dismiss()
                    } finally { working = false }
                }
            }) { Text("恢复本话默认封面") }
        }
    }, confirmButton = { AppTextButton(dismiss, enabled = !working) { Text(if (working) "正在保存…" else "关闭") } })
}

@Composable internal fun ComicChapterCover(ref: BookRef, chapterId: String, title: String, fallback: @Composable () -> Unit) {
    val cover = rememberCustomBookCover(ref, chapterId)
    if (cover == null) fallback()
    else {
        val context = LocalContext.current
        val request = remember(context, cover) { ImageRequest.Builder(context).data(cover.file)
            .memoryCacheKey("${cover.file.absolutePath}:${cover.revision}").build() }
        AsyncImage(request, "$title 自定义封面", Modifier.size(width = 40.dp, height = 56.dp).clip(appBookCoverShape()),
            contentScale = ContentScale.Crop)
    }
}
