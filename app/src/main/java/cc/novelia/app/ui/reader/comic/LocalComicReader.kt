package cc.novelia.app.ui.reader.comic

import android.graphics.BitmapFactory
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.ComicReadingMode
import cc.novelia.app.data.model.comicArchiveFormats
import cc.novelia.app.data.model.hasOnlyImagePages
import cc.novelia.app.data.model.Position
import cc.novelia.app.data.storage.LocalStore
import cc.novelia.app.reader.ComicPage
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.reader.readerInteractionModifier
import cc.novelia.app.ui.reader.rememberReaderReadingTime
import cc.novelia.app.ui.theme.activityOrNull
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class ComicSection(val id: String, val title: String, val first: Int, val count: Int)
internal data class LocalComicVolume(val title: String, val pages: List<ComicPage>, val sections: List<ComicSection>)

/** 纯图片 EPUB 跨 spine 连续翻页，保存位置时仍使用原书的章节 ID 与章内下标。 */
internal suspend fun LocalStore.loadComicVolume(id: String): LocalComicVolume? = withContext(Dispatchers.IO) {
    val doc = documentIndex(id)
    if (doc.format !in comicArchiveFormats && (doc.format != "epub" || doc.imageOnlyEpub == false)) return@withContext null
    val sections = mutableListOf<ComicSection>()
    val hashes = mutableListOf<String>()
    for (entry in doc.chapters) {
        ensureActive()
        val chapter = documentChapter(id, entry.id)
        if (!chapter.hasOnlyImagePages()) {
            if (doc.format == "epub") return@withContext null
            throw IllegalArgumentException("漫画章节中的图片索引无效")
        }
        sections += ComicSection(entry.id, entry.title, hashes.size, chapter.paragraphs.size)
        chapter.paragraphs.mapTo(hashes) { it.removePrefix("novelia-image:") }
    }
    require(hashes.isNotEmpty()) { "这一册没有漫画页" }
    val pages = hashes.map { hash ->
        ensureActive()
        readComicPage(documentImage(id, hash))
    }
    LocalComicVolume(doc.name, pages, sections)
}

@Composable
internal fun LocalComicReader(c: AppController, ref: BookRef, book: LocalComicVolume, chapterId: String) {
    key(ref, chapterId) {
        val state by c.store.state.collectAsStateWithLifecycle()
        val chapter = remember(book, chapterId) { book.sections.firstOrNull { it.id == chapterId } ?: book.sections.first() }
        val activity = LocalContext.current.activityOrNull()
        DisposableEffect(activity) {
            val bars = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
            val light = bars?.isAppearanceLightStatusBars
            bars?.isAppearanceLightStatusBars = false
            onDispose { if (light != null) bars.isAppearanceLightStatusBars = light }
        }
        val saved = remember(ref, chapter.id) { state.positions[ref.key]?.takeIf { it.chapterId == chapter.id } }
        val interact = rememberReaderReadingTime(c, ref.key, book.title, state.readingStatistics.revision,
            eligible = state.readingStatistics.enabled && !state.historyPaused, automatic = false)
        ComicReader(
            title = book.title, pages = book.pages, mode = state.comicReadingMode,
            initialPage = chapter.first + ((saved?.index ?: 1) - 1).coerceIn(0, chapter.count - 1), initialOffset = saved?.offset ?: 0,
            onMode = { mode -> c.store.update { it.copy(comicReadingMode = mode) } },
            onPosition = { page, sourceY, completed ->
                val hit = book.sections.binarySearchBy(page) { it.first }
                val ordinal = if (hit >= 0) hit else -hit - 2
                val section = book.sections[ordinal]
                val within = page - section.first + 1
                c.store.savePosition(ref, Position(section.id, within, offset = sourceY, title = section.title,
                    chapterIndex = ordinal, chapterCount = book.sections.size, paragraphCount = section.count,
                    chapterCompleted = completed || (ordinal < book.sections.lastIndex && within == section.count &&
                        state.comicReadingMode != ComicReadingMode.Webtoon)), book.title)
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
    return ComicPage(file, width, height, orientation,
        regionDecodable = bounds.outMimeType in setOf("image/jpeg", "image/png", "image/webp"))
}
