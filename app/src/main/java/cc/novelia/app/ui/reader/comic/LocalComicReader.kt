package cc.novelia.app.ui.reader.comic

import android.graphics.BitmapFactory
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LocalDocument
import cc.novelia.app.data.model.Position
import cc.novelia.app.reader.ComicPage
import cc.novelia.app.ui.components.base.AsyncContent
import cc.novelia.app.ui.components.base.EmptyState
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.reader.readerInteractionModifier
import cc.novelia.app.ui.reader.rememberReaderReadingTime
import cc.novelia.app.ui.theme.activityOrNull
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Composable
internal fun LocalComicReader(c: AppController, ref: BookRef, doc: LocalDocument, chapterId: String) {
    val state by c.store.state.collectAsStateWithLifecycle()
    val ordinal = doc.chapters.indexOfFirst { it.id == chapterId }.coerceAtLeast(0)
    val chapter = doc.chapters.getOrNull(ordinal)
    if (chapter == null) {
        EmptyState("没有可阅读的漫画页", "请重新导入 CBZ 文件", action = "返回", onAction = c::back)
        return
    }
    AsyncContent(ref to chapter.id, load = {
        withContext(Dispatchers.IO) {
            val paragraphs = c.store.documentChapter(ref.id, chapter.id).paragraphs
            require(paragraphs.isNotEmpty()) { "这一章没有漫画页" }
            paragraphs.map { marker ->
                ensureActive()
                readComicPage(c.store.documentImage(ref.id, marker.removePrefix("novelia-image:")))
            }
        }
    }) { pages, _ ->
        val activity = LocalContext.current.activityOrNull()
        DisposableEffect(activity) {
            val bars = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
            val light = bars?.isAppearanceLightStatusBars
            bars?.isAppearanceLightStatusBars = false
            onDispose { if (light != null) bars.isAppearanceLightStatusBars = light }
        }
        val saved = remember(ref, chapter.id) { state.positions[ref.key]?.takeIf { it.chapterId == chapter.id } }
        val interact = rememberReaderReadingTime(c, ref.key, doc.name, state.readingStatistics.revision,
            eligible = state.readingStatistics.enabled && !state.historyPaused, automatic = false)
        ComicReader(
            title = doc.name, pages = pages, mode = state.comicReadingMode,
            initialPage = ((saved?.index ?: 1) - 1).coerceIn(pages.indices), initialOffset = saved?.offset ?: 0,
            onMode = { mode -> c.store.update { it.copy(comicReadingMode = mode) } },
            onPosition = { page, sourceY, completed ->
                c.store.savePosition(ref, Position(chapter.id, page + 1, offset = sourceY, title = chapter.title,
                    chapterIndex = ordinal, chapterCount = doc.chapters.size, paragraphCount = pages.size,
                    chapterCompleted = completed), doc.name)
            }, onBack = c::back, modifier = readerInteractionModifier(interact)
        )
    }
}

private fun readComicPage(file: File): ComicPage {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    val orientation = if (bounds.outMimeType == "image/jpeg") {
        try { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) } catch (_: IOException) { 1 }
    } else 1
    val swap = orientation in 5..8
    val width = if (swap) bounds.outHeight else bounds.outWidth
    val height = if (swap) bounds.outWidth else bounds.outHeight
    // 缺失、损坏或尺寸异常的单页保留原页码并显示加载失败状态。
    if (width <= 0 || height <= 0 || height.toLong() > width.toLong() * 512) return ComicPage(file, 3, 4)
    return ComicPage(file, width, height, orientation)
}
