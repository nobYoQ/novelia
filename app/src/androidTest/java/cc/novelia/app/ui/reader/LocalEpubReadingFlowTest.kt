package cc.novelia.app.ui.reader

import androidx.compose.ui.unit.Density
import android.content.Context
import android.content.ContextWrapper
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.model.*
import cc.novelia.app.data.storage.LocalStore
import cc.novelia.app.files.DocumentTools
import cc.novelia.app.files.importing.importDownloadedDocument
import cc.novelia.app.files.importing.importLocalDocument
import cc.novelia.app.reader.projectParagraphs
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 用独立书库验证下载导入、旧章迁移和重启，不改动用户书架。 */
@RunWith(AndroidJUnit4::class)
class LocalEpubReadingFlowTest {
    private class IsolatedContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
    }

    private fun epub(body: String = "<p style='opacity:0.4;'>日本語</p><p>中文</p><p style='opacity:0.4;'>次の段</p><p>下一段</p>",
        nav: String? = null, repeatSpine: Boolean = false) = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
        val navigation = if (nav == null) "" else "<item id='nav' href='nav.xhtml' properties='nav' media-type='application/xhtml+xml'/>"
        val repeated = if (repeatSpine) "<itemref idref='c'/>" else ""
        val files = mutableMapOf(
            "META-INF/container.xml" to "<container><rootfile full-path='book.opf'/></container>",
            "book.opf" to "<package><manifest><item id='c' href='c.xhtml'/>$navigation</manifest><spine><itemref idref='c'/>$repeated</spine></package>",
            "c.xhtml" to "<html><body>$body</body></html>",
        )
        if (nav != null) files["nav.xhtml"] = "<html><body><nav epub:type='toc'>$nav</nav></body></html>"
        files.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
    } }.toByteArray()

    private fun entry() = DownloadEntry("download", "第一卷", "download.epub", "https://example.test/file?mode=jp-zh",
        status = "已完成", sourceBook = BookRef("wenku", "series"))

    @Test fun importedPairsApplySecondaryOpacityOnlyToJapaneseInBilingualLayouts() {
        val chapter = DocumentTools.parse("卷.epub", epub(), "jp-zh").chapters.single().toReaderChapter()
        for (mode in listOf("zh", "jp", "jp-zh", "zh-jp")) {
            val settings = ReaderSettings(mode = mode, secondaryAlpha = .45f, indent = false)
            val paragraphs = projectParagraphs(chapter, settings)
            val text = measureEInkChapter(paragraphs, settings, 400, 1000, Density(1f)).layouts.getValue(0).text as Spanned
            fun alpha(word: String): Int {
                val start = text.indexOf(word)
                assertTrue(start >= 0)
                val paint = TextPaint().apply { alpha = 255 }
                text.getSpans(start, start + word.length, CharacterStyle::class.java).forEach { it.updateDrawState(paint) }
                return paint.alpha
            }
            if (mode == "zh") assertFalse(text.contains("日本語"))
            else assertEquals(if (mode == "jp") 255 else (255 * .45f).toInt(), alpha("日本語"))
            if (mode == "jp") assertFalse(text.contains("中文"))
            else assertEquals(255, alpha("中文"))
        }
    }

    private suspend fun fixture(block: suspend (Context, LocalStore) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "local-epub-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = IsolatedContext(base, root)
        val store = LocalStore(context)
        try { block(context, store) }
        finally { store.flush(); delay(350); root.deleteRecursively() }
    }

    @Test fun downloadedAndDuplicateExternalImportsUseRequestModeAndKeepTheSameBook() = runBlocking {
        withContext(Dispatchers.IO) { fixture { _, store ->
            val entry = entry()
            val file = File(store.downloadsDir, entry.fileName).apply { writeBytes(epub()) }
            val external = importLocalDocument(store, file).ref
            val downloaded = importDownloadedDocument(store, entry)
            assertEquals(external, downloaded)
            assertEquals("jp-zh", store.documentIndex(downloaded.id).downloadMode)
            val chapter = store.documentChapter(downloaded.id, "c").toReaderChapter()
            assertEquals(listOf("中文", "下一段"), projectParagraphs(chapter, ReaderSettings(engines = emptyList())).flatMap { it.parts }.map { it.text })
            assertEquals(listOf("日本語", "次の段"), projectParagraphs(chapter, ReaderSettings(mode = "jp")).flatMap { it.parts }.map { it.text })
            assertEquals(1, store.state.value.books.count { it.book.ref.isLocal })
        } }
    }

    @Test fun legacyDownloadRecoversFromExactSourceAndKeepsProgressAfterRestart() = runBlocking {
        withContext(Dispatchers.IO) { fixture { context, store ->
            val entry = entry()
            val bytes = epub()
            File(store.downloadsDir, entry.fileName).writeBytes(bytes)
            val parsed = DocumentTools.parse(entry.fileName, bytes)
            val old = parsed.copy(id = "legacy", chapters = parsed.chapters.map { it.copy(readingContent = LocalReadingContent(), epubContentVersion = 0) })
            store.saveDocument(old)
            store.documentSource(old.id, "epub").writeBytes(bytes)
            val ref = BookRef("local", old.id)
            val position = Position("c", index = 4, offset = 9, updatedAt = 123)
            store.update { it.copy(downloads = listOf(entry), books = listOf(SavedBook(BookCard(ref, "用户改过的名字"))),
                positions = mapOf(ref.key to position), notes = listOf(Note("note", ref.key, "c", 3, "下一段", "笔记"))) }
            val recovered = store.documentChapter(old.id, "c")
            assertEquals(2, recovered.readingContent.groups.size)
            assertEquals(old.chapters.single().paragraphs, recovered.paragraphs)
            assertEquals(position.copy(index = 2, offset = 0, textOffset = 0, paragraphCount = 2), store.state.value.positions[ref.key])
            assertEquals(2, store.state.value.notes.single().paragraph)
            store.flush()
            val reopened = LocalStore(context)
            assertEquals("jp-zh", reopened.documentIndex(old.id).downloadMode)
            assertEquals(recovered, reopened.documentChapter(old.id, "c"))
            assertEquals(store.state.value.positions, reopened.state.value.positions)
            reopened.flush()
        } }
    }

    @Test fun missingOriginalStillReadsOnceInEveryLanguageMode() = runBlocking {
        withContext(Dispatchers.IO) { fixture { _, store ->
            val old = LocalDocument("missing", "没有原件", "epub", listOf(LocalChapter("c", "章", listOf("日本語", "中文"))))
            store.saveDocument(old)
            for (mode in listOf("zh", "jp", "jp-zh", "zh-jp")) {
                val chapter = store.documentChapter(old.id, "c").toReaderChapter()
                assertEquals(listOf("日本語", "中文"), projectParagraphs(chapter, ReaderSettings(mode = mode)).flatMap { it.parts }.map { it.text })
            }
        } }
    }

    @Test fun legacyChapterTitleIsUpdatedOnOpeningTheTocWithoutMovingProgressOrNotes() = runBlocking {
        withContext(Dispatchers.IO) { fixture { context, store ->
            val bytes = epub("<h1 id='first'>第一章</h1><p id='second'>这一段有阅读进度和笔记。</p>",
                nav = """<ol><li><span>上卷</span><ol><li><a href="c.xhtml#first">第一章</a></li>
                    <li><a href="c.xhtml#second">小节</a></li></ol></li></ol>""")
            val parsed = DocumentTools.parse("卷.epub", bytes)
            val original = parsed.chapters.single()
            val old = parsed.copy(id = "old-titles", epubTitleVersion = 2, epubNavigation = emptyList(),
                chapters = listOf(original.copy(title = "卷名", epubContentVersion = 4)))
            store.saveDocument(old)
            store.documentSource(old.id, "epub").writeBytes(bytes)
            val key = BookRef("local", old.id).key
            val position = Position("c", index = 2, offset = 87, textOffset = 4, updatedAt = 123, sourceParagraph = 1)
            val note = Note("note", key, "c", 1, "阅读进度", "保留这条笔记", selection = NoteSelection("原文", start = 4, end = 8))
            store.update { it.copy(positions = mapOf(key to position), notes = listOf(note)) }

            val index = store.documentIndex(old.id)
            assertEquals("第一章", index.chapters.single().title)
            assertEquals(listOf("上卷", "第一章", "小节"), index.navigationToc().map { it.title })
            assertEquals(listOf(null, "first", "second"), index.navigationToc().map { it.epubFragment })
            assertEquals(original, store.documentChapter(old.id, "c"))
            store.flush()

            val reopened = LocalStore(context)
            assertEquals("第一章", reopened.documentIndex(old.id).chapters.single().title)
            assertEquals(index.epubNavigation, reopened.documentIndex(old.id).epubNavigation)
            assertEquals(original, reopened.documentChapter(old.id, "c"))
            assertEquals(position, reopened.state.value.positions[key])
            assertEquals(listOf(note), reopened.state.value.notes)
        } }
    }

    @Test fun repeatedSpineOccurrencesAreStoredAndCanBeAnnotatedSeparately() = runBlocking {
        withContext(Dispatchers.IO) { fixture { context, store ->
            val source = File(context.cacheDir, "重复章节.epub").apply { writeBytes(epub("<p>重复正文</p>", repeatSpine = true)) }
            val ref = importLocalDocument(store, source).ref
            val chapters = store.documentIndex(ref.id).chapters.map { it.id }
            assertEquals(listOf("c", "c~2"), chapters)
            val notes = chapters.map { chapter -> Note("note-$chapter", ref.key, chapter, 0, "重复正文", chapter) }
            val position = Position(chapters.last(), index = 1, sourceParagraph = 0)
            store.update { it.copy(notes = notes, positions = mapOf(ref.key to position)) }
            store.flush()
            val reopened = LocalStore(context)
            assertEquals(position, reopened.state.value.positions[ref.key])
            assertEquals(notes, reopened.state.value.notes)
            assertEquals(listOf("重复正文"), reopened.documentChapter(ref.id, chapters.last()).paragraphs)
        } }
    }

    @Test fun rubyWhitespaceInExistingBilingualCacheIsRepairedBeforeLayoutAndSurvivesRestart() = runBlocking {
        withContext(Dispatchers.IO) { fixture { context, store ->
            val japanese = "地方勤務の聖騎士"
            val polluted = japanese.toCharArray().joinToString("\n    \n  ")
            val markup = japanese.toCharArray().joinToString("") { "<ruby>\n <rb>\n  $it\n </rb><rt>よみ</rt>\n</ruby>" }
            val bytes = epub("<p>地方公务员圣骑士</p><p style='opacity:0.4;'>$markup</p>")
            val parsed = DocumentTools.parse("卷.epub", bytes, "zh-jp")
            val old = parsed.copy(id = "ruby-legacy", chapters = parsed.chapters.map {
                it.copy(paragraphs = listOf("地方公务员圣骑士", polluted), epubContentVersion = 1)
            })
            store.saveDocument(old)
            store.documentSource(old.id, "epub").writeBytes(bytes)
            val ref = BookRef("local", old.id)
            val position = Position("c", index = 1, offset = 600, textOffset = 10, updatedAt = 123)
            store.update { it.copy(positions = mapOf(ref.key to position)) }
            val recovered = store.documentChapter(old.id, "c")
            assertEquals(listOf("地方公务员圣骑士", japanese), recovered.paragraphs)
            assertEquals(position.copy(offset = 0, textOffset = 0, paragraphCount = 1), store.state.value.positions[ref.key])
            for (mode in listOf("zh", "jp", "zh-jp", "jp-zh")) {
                val settings = ReaderSettings(mode = mode, indent = false, fontSize = 20f)
                val paragraphs = projectParagraphs(recovered.toReaderChapter(), settings)
                val layout = measureEInkChapter(paragraphs, settings, 400, 1000, Density(1f)).layouts.getValue(0)
                assertEquals(if (mode.contains('-')) 2 else 1, layout.lineCount)
            }
            store.flush()
            val reopened = LocalStore(context)
            assertEquals(recovered, reopened.documentChapter(old.id, "c"))
            assertEquals(store.state.value.positions, reopened.state.value.positions)
            reopened.flush()
        } }
    }
}
