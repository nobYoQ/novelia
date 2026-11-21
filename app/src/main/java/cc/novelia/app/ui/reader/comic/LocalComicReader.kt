package cc.novelia.app.ui.reader.comic

import android.graphics.BitmapFactory
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.library.mountedVolumes
import cc.novelia.app.data.model.*
import cc.novelia.app.files.comic.ComicPageCache

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
internal data class LocalComicVolume(val title: String, val pages: List<ComicPage>, val sections: List<ComicSection>,
    val archiveSeries: Boolean = false,
) {
    val hasChapterCovers get() = archiveSeries || sections.size > 1
}

internal fun List<ComicSection>.sectionIndexAt(page: Int): Int {
    val hit = binarySearchBy(page) { it.first }
    return if (hit >= 0) hit else -hit - 2
}

/** 纯图片 EPUB 跨 spine 连续翻页，保存位置时仍使用原书的章节 ID 与章内下标。 */
internal suspend fun LocalStore.loadComicVolume(id: String): LocalComicVolume? = withContext(Dispatchers.IO) {
    val doc = documentIndex(id)
    val override = state.value.books.firstOrNull { it.book.ref == BookRef("local", id) }?.epubMediumOverride
    if (doc.format !in comicArchiveFormats && (doc.format != "epub" || override == BookMedium.Novel ||
        override == null && doc.imageOnlyEpub == false)) return@withContext null
    val source = documentSource(id, doc.format)
    val cache = ComicPageCache(File(context.cacheDir, "comic-pages"), source, doc.format, doc.sourceHash)
    val sections = mutableListOf<ComicSection>()
    val hashes = mutableListOf<String>()
    for (entry in doc.chapters) {
        ensureActive()
        val chapter = documentChapter(id, entry.id)
        if (doc.archivePages.isEmpty() && override != BookMedium.Comic && !chapter.hasOnlyImagePages()) {
            if (doc.format == "epub") return@withContext null
            throw IllegalArgumentException("漫画章节中的图片索引无效")
        }
        if (chapter.paragraphs.isEmpty()) continue
        sections += ComicSection(entry.id, entry.title, hashes.size, chapter.paragraphs.size)
        hashes.addAll(chapter.paragraphs)
    }
    require(hashes.isNotEmpty()) { "这一册没有漫画页" }
    val pages = hashes.map { hash ->
        ensureActive()
        when {
            hash.startsWith("novelia-archive:") && doc.archivePages.isNotEmpty() -> {
                val page = doc.archivePages[hash.substringAfter(':').toInt()]
                ComicPage(source, page.width, page.height, page.orientation, page.regionDecodable,
                    load = { cache.load(page.path) })
            }
            hash.matches(imageToken) -> readComicPage(documentImage(id, hash.substringAfter(':')))
            else -> ComicPage(File(""), 3, 4, text = hash)
        }
    }
    LocalComicVolume(doc.name, pages, sections, doc.comicSeries)
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
        val savedBook = state.books.firstOrNull { it.book.ref == ref }
        val preferences = savedBook?.comicPreferences ?: ComicPreferences()
        val mode = preferences.mode ?: state.comicReadingMode
        val series = remember(state.books, savedBook?.parentWenkuKey) { state.books.firstOrNull {
            it.book.ref.key == savedBook?.parentWenkuKey && it.book.ref.isLocalSeries && it.book.shelfMedium == BookMedium.Comic
        } }
        val volumes = remember(state.books, series) { series?.let { state.mountedVolumes(it) }
            .orEmpty().filter { it.book.shelfMedium == BookMedium.Comic } }
        val volumeIndex = volumes.indexOfFirst { it.book.ref == ref }
        var openingVolume by remember { mutableStateOf(false) }
        fun openVolume(target: BookRef) {
            if (openingVolume) return
            openingVolume = true
            val reader = c.nav.currentBackStackEntry
            c.action {
                try {
                    val id = withContext(Dispatchers.IO) {
                        val entries = c.store.documentIndex(target.id).chapters
                        val savedId = c.store.state.value.positions[target.key]?.chapterId
                        entries.firstOrNull { it.id == savedId }?.id ?: entries.firstOrNull()?.id ?: error("这话没有可阅读的页面")
                    }
                    if (c.nav.currentBackStackEntry === reader) {
                        c.back()
                        c.read(target, id)
                    }
                } finally { openingVolume = false }
            }
        }
        var coverPage by rememberSaveable { mutableStateOf<Int?>(null) }
        ComicReader(
            title = book.title, pages = book.pages, mode = mode, preferences = preferences, sections = book.sections,
            defaultMode = state.comicReadingMode, onDefaultMode = { next -> c.store.update { it.copy(comicReadingMode = next) } },
            onDetails = { c.showBookDetails(ref) },
            initialPage = chapter.first + ((saved?.index ?: 1) - 1).coerceIn(0, chapter.count - 1), initialOffset = saved?.offset ?: 0,
            onPreferences = { next -> c.store.update { library -> library.copy(books = library.books.map {
                if (it.book.ref == ref) it.copy(comicPreferences = next) else it
            }) } },
            onPosition = { page, sourceY, completed ->
                val ordinal = book.sections.sectionIndexAt(page)
                val section = book.sections[ordinal]
                val within = page - section.first + 1
                c.store.savePosition(ref, Position(section.id, within, offset = sourceY, title = section.title,
                    chapterIndex = ordinal, chapterCount = book.sections.size, paragraphCount = section.count, sourceParagraph = within - 1,
                    chapterCompleted = completed || (ordinal < book.sections.lastIndex && within == section.count &&
                        mode != ComicReadingMode.Webtoon)), book.title)
            }, onBack = c::back, modifier = readerInteractionModifier(interact),
            coverRef = ref.takeIf { book.hasChapterCovers }, onCover = { coverPage = it },
            previousVolume = volumes.getOrNull(volumeIndex - 1)?.let { volume -> { openVolume(volume.book.ref) } },
            nextVolume = volumes.getOrNull(volumeIndex + 1)?.takeIf { volumeIndex >= 0 }?.let { volume -> { openVolume(volume.book.ref) } },
            onSeriesDetails = series?.let { parent -> { c.showBookDetails(parent.book.ref) } },
        )
        coverPage?.takeIf { it in book.pages.indices }?.let { page ->
            ComicCoverDialog(c, ref, book, page, series?.book) { coverPage = null }
        }
    }
}

private val imageToken = Regex("novelia-image:[a-f0-9]{64}")

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
