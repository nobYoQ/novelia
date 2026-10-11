package cc.novelia.app.data.storage

import cc.novelia.app.data.model.*
import cc.novelia.app.files.DocumentTools
import cc.novelia.app.files.epub.contentMode
import cc.novelia.app.files.epub.recoverEpubChapter
import cc.novelia.app.reader.findReadingTextMatches
import cc.novelia.app.reader.prepareReadingParagraphs
import cc.novelia.app.reader.projectParagraphs
import cc.novelia.app.reader.readingSourceAnchor
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalEpubReadingTest {
    @get:Rule val folder = TemporaryFolder()
    private fun epub(body: String): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            mapOf(
                "META-INF/container.xml" to "<container><rootfile full-path='OPS/book.opf'/></container>",
                "OPS/book.opf" to "<package><manifest><item id='c' href='c.xhtml'/></manifest><spine><itemref idref='c'/></spine></package>",
                "OPS/c.xhtml" to "<html><head><title>章节</title></head><body>$body</body></html>",
                "OPS/image.png" to "image-content",
            ).forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
        }
    }.toByteArray()

    private fun source(bytes: ByteArray): File = folder.newFile().apply { writeBytes(bytes) }
    private fun parse(body: String, mode: String? = "jp-zh") = DocumentTools.parse("卷.epub", epub(body), mode).chapters.single()
    private fun project(chapter: LocalChapter, mode: String, parallel: Boolean = false) =
        projectParagraphs(chapter.toReaderChapter(), ReaderSettings(mode = mode, engines = emptyList(), parallel = parallel))
    private val japanese = "<p style='opacity:0.4;'>日本語</p>"
    private val chinese = "<p>中文</p>"

    @Test fun bothDownloadOrdersFollowAllFourReadingModesWithoutDuplicatingText() {
        for (order in listOf("jp-zh", "zh-jp")) {
            val chapter = parse(if (order == "jp-zh") japanese + chinese else chinese + japanese, order)
            assertEquals(listOf("中文"), project(chapter, "zh").single().parts.map { it.text })
            assertEquals(listOf("日本語"), project(chapter, "jp").single().parts.map { it.text })
            assertFalse(project(chapter, "jp").single().parts.single().secondary)
            for (mode in listOf("jp-zh", "zh-jp")) {
                val paragraph = project(chapter, mode).single()
                assertEquals(if (mode == "jp-zh") listOf("日本語", "中文") else listOf("中文", "日本語"), paragraph.parts.map { it.text })
                assertEquals(listOf("日本語"), paragraph.parts.filter { it.secondary }.map { it.text })
                assertFalse(paragraph.fallback)
            }
        }
    }

    @Test fun diskAndMemoryImportsKeepTheSameBilingualInformationAndRawText() {
        val bytes = epub(japanese + chinese + "<img src='image.png'/>" + japanese + chinese)
        val memory = DocumentTools.parse("卷.epub", bytes, "jp-zh")
        val disk = DocumentTools.parseFile("卷.epub", source(bytes), { _, _ -> }, downloadMode = "jp-zh")
        assertEquals(memory.chapters, disk.chapters)
        assertEquals("jp-zh", disk.downloadMode)
        val chapter = disk.chapters.single()
        assertEquals(5, chapter.paragraphs.size)
        for (mode in listOf("zh", "jp", "zh-jp", "jp-zh")) {
            val projected = project(chapter, mode)
            assertEquals(listOf(0, 2, 3), projected.map { it.index })
            assertNotNull(projected[1].localImageId)
        }
    }

    @Test fun embeddedParallelTranslationsAreNotLostOrMislabeledAsAnOnlineEngine() {
        val chapter = parse("<p>译文一</p><p>译文二</p>$japanese", "zh-jp")
        for (parallel in listOf(false, true)) {
            assertEquals(listOf("译文一", "译文二"), project(chapter, "zh", parallel).single().parts.map { it.text })
            assertEquals(listOf("译文", "译文", "日文"), project(chapter, "zh-jp", parallel).single().parts.map { it.source })
        }
    }

    @Test fun lineBreaksRubyAndInlineMarkupStayInTheirLanguagePart() {
        val chapter = parse("<p style='opacity: .4;'>日本<ruby>語<rt>ご</rt></ruby><br/>二行目</p><p>中<b>文</b><br/>第二行</p>")
        assertEquals(listOf("日本語\n二行目", "中文\n第二行"), project(chapter, "jp-zh").single().parts.map { it.text })
        assertEquals(4, chapter.paragraphs.size)
    }

    @Test fun prettyPrintedRubyKeepsJapaneseTextOnOneContinuousLine() {
        val body = """
            <p>地方公务员圣骑士</p>
            <p style="opacity:0.4;">
              <ruby>
                <rb>
                  地
                </rb>
                <rt>ち</rt>
              </ruby>
              <ruby>
                <rb>
                  方
                </rb>
                <rt>ほう</rt>
              </ruby>
              <ruby>
                勤
                <rt>きん</rt>
              </ruby>
              <ruby>
                務
                <rt>む</rt>
              </ruby>の
              <ruby>
                聖
                <rt>せい</rt>
              </ruby>
              <ruby>
                騎
                <rt>き</rt>
              </ruby>
              <ruby>
                士
                <rt>し</rt>
              </ruby>
            </p>
        """.trimIndent()
        val chapter = parse(body, "zh-jp")
        assertEquals(chapter, DocumentTools.parseFile("卷.epub", source(epub(body)), { _, _ -> }, downloadMode = "zh-jp").chapters.single())
        assertEquals(listOf("地方公务员圣骑士", "地方勤務の聖騎士"), chapter.paragraphs)
        val displayed = project(chapter, "zh-jp").single()
        assertEquals(listOf("地方公务员圣骑士", "地方勤務の聖騎士"), displayed.parts.map { it.text })
        assertTrue(displayed.parts.last().secondary)
    }

    @Test fun normalFlowCollapsesSourceWhitespaceWithoutLosingWordSpacesOrAuthoredBreaks() {
        val chapter = parse("<p>Hello\n <em>world</em> and\t friends.</p><p>co<em>op</em>eration</p>" +
            "<p>日 本\n語　中&nbsp;文</p><p>𠀀\r\n𠀁</p><p>カ\nー\nド</p><p>第一行<br/>第二行</p><p>別段</p><pre>foo\n  bar</pre>")
        assertEquals(listOf("Hello world and friends.", "cooperation", "日 本語　中\u00a0文", "𠀀𠀁", "カード",
            "第一行", "第二行", "別段", "foo\n  bar"), chapter.paragraphs)
    }

    @Test fun whitespaceUpgradeKeepsParagraphAnchorsAndRepairsOffsetsEvenWithUnchangedPairs() {
        val bytes = epub("<p>圣骑士</p><p style='opacity:0.4;'><ruby><rb>\n 地\n </rb><rt>ち</rt></ruby>方</p>")
        val parsed = DocumentTools.parse("卷.epub", bytes, "zh-jp").chapters.single()
        val old = parsed.copy(paragraphs = listOf("圣骑士", "地\n \n 方"), epubContentVersion = 1)
        val restored = recoverEpubChapter(source(bytes), old, "zh-jp")
        assertEquals(listOf("圣骑士", "地方"), restored.paragraphs)
        assertEquals(old.readingContent, restored.readingContent)
        assertTrue(restored.epubContentVersion > old.epubContentVersion)
        val position = Position("c", index = 1, offset = 300, textOffset = 5, updatedAt = 123)
        val note = Note("note", "local/book", "c", 0, "圣骑士", "笔记")
        val state = LibraryState(positions = mapOf("local/book" to position), notes = listOf(note))
        val migrated = state.withRecoveredEpubChapter("book", old, restored)
        assertEquals(position.copy(offset = 0, textOffset = 0, paragraphCount = 1), migrated.positions["local/book"])
        assertEquals(listOf(note), migrated.notes)
        assertEquals(migrated, migrated.withRecoveredEpubChapter("book", restored, restored))
        assertThrows(IllegalArgumentException::class.java) {
            recoverEpubChapter(source(bytes), old.copy(paragraphs = listOf("圣骑士", "地 方改动")), "zh-jp")
        }
    }

    @Test fun unknownFilesNeverGuessLanguageFromOpacityFilenameOrAlternatingLines() {
        val chapter = parse(japanese + chinese, null)
        assertTrue(chapter.readingContent.groups.isEmpty())
        for (mode in listOf("zh", "jp", "zh-jp", "jp-zh")) {
            val paragraphs = project(chapter, mode)
            assertEquals(listOf("日本語", "中文"), paragraphs.flatMap { it.parts }.map { it.text })
            assertTrue(paragraphs.first().parts.single().secondary)
            assertTrue(paragraphs.all { it.parts.size == 1 })
        }
        val text = DocumentTools.parse("jp-zh.卷.txt", "日本語\n中文".toByteArray()).chapters.single()
        assertEquals(2, project(text, "jp-zh").sumOf { it.parts.size })
    }

    @Test fun explicitAdjacentLanguagesCanIdentifyAnExternalEpubWithoutDownloadHistory() {
        val chapter = parse("<p xml:lang='ja'>日本語</p><p lang='zh-CN'>中文</p>", null)
        assertEquals(listOf("中文"), project(chapter, "zh").single().parts.map { it.text })
    }

    @Test fun headingsImagesAndMissingTranslationsRemainVisibleWithoutFalsePairs() {
        val chapter = parse("<h1>标题</h1>$japanese<img src='image.png'/>$chinese<div>旁白</div><p>未翻訳</p>")
        assertTrue(chapter.readingContent.groups.isEmpty())
        assertEquals(6, project(chapter, "zh-jp").size)
        assertEquals("未翻訳", project(chapter, "zh").last().parts.single().text)
    }

    @Test fun traditionalConversionAndSearchUseOnlyTheSelectedLanguage() {
        val chapter = parse("<p style='opacity:0.4;'>日本語の竜</p><p>阅读龙</p>")
        val prepared = prepareReadingParagraphs(chapter.toReaderChapter(), ReaderSettings(mode = "zh-jp", traditional = true))
        assertEquals(listOf("閱讀龍", "日本語の竜"), prepared.single().parts.map { it.text })
        assertTrue(findReadingTextMatches(project(chapter, "zh"), "日本語").isEmpty())
        assertEquals(1, findReadingTextMatches(project(chapter, "jp-zh"), "日本語").size)
    }

    @Test fun malformedOrOverlappingPairMetadataCannotDropOrDuplicateText() {
        val chapter = LocalChapter("c", "章", listOf("甲", "乙", "丙"), LocalReadingContent(groups = listOf(
            LocalBilingualGroup(listOf(0), listOf(listOf(9))),
            LocalBilingualGroup(listOf(0), listOf(listOf(0))),
            LocalBilingualGroup(listOf(0), listOf(listOf(2))),
        )))
        assertEquals(listOf("甲", "乙", "丙"), project(chapter, "zh-jp").flatMap { it.parts }.map { it.text })
    }

    @Test fun olderJsonRemainsReadableAndPortableJsonKeepsBilingualData() {
        val old = appJson.decodeFromString<LocalChapter>("""{"id":"c","title":"章","paragraphs":["正文"]}""")
        assertEquals("正文", project(old, "jp-zh").single().parts.single().text)
        val chapter = parse(japanese + chinese)
        assertEquals(chapter, appJson.decodeFromString<LocalChapter>(appJson.encodeToString(chapter)))
    }

    @Test fun legacyRecoveryPreservesRawIndicesAndRejectsChangedOriginals() {
        val bytes = epub(japanese + chinese)
        val old = LocalChapter("c", "旧标题", listOf("日本語", "中文"))
        val restored = recoverEpubChapter(source(bytes), old, "jp-zh")
        assertEquals(old.paragraphs, restored.paragraphs)
        assertEquals(old.title, restored.title)
        assertEquals(1, project(restored, "zh").size)
        assertThrows(IllegalArgumentException::class.java) { recoverEpubChapter(source(epub("<p>改动</p>")), old, "jp-zh") }
    }

    @Test fun legacyProgressAndNotesMoveToTheSamePairWithoutChangingReadingTime() {
        val restored = parse(japanese + chinese + japanese.replace("日本語", "次の段") + chinese.replace("中文", "下一段"))
        val old = restored.copy(readingContent = LocalReadingContent(), epubContentVersion = 0)
        val position = Position("c", index = 4, offset = 30, textOffset = 2, updatedAt = 123)
        val state = LibraryState(positions = mapOf("local/book" to position), notes = listOf(Note("note", "local/book", "c", 3, "下一段", "笔记")))
        val migrated = state.withRecoveredEpubChapter("book", old, restored)
        assertEquals(position.copy(index = 2, offset = 0, textOffset = 0, paragraphCount = 2), migrated.positions["local/book"])
        assertEquals(2, migrated.notes.single().paragraph)
        assertEquals(2, readingSourceAnchor(restored.toReaderChapter(), 3))
    }

    @Test fun sourceModeComesFromKnownDownloadParametersAndParsingCanBeCancelled() {
        val entry = DownloadEntry("d", "卷", "misleading.epub", "https://example.test/file?mode=zh-jp", sourceBook = BookRef("wenku", "book"))
        assertEquals("zh-jp", entry.contentMode())
        assertEquals("zh-jp", entry.copy(sourceBook = null).contentMode())
        assertNull(entry.copy(url = "https://example.test/file?mode=unknown").contentMode())
        var checks = 0
        assertThrows(CancellationException::class.java) {
            DocumentTools.parseFile("卷.epub", source(epub((japanese + chinese).repeat(100))), { _, _ -> }, downloadMode = "jp-zh") {
                if (++checks == 30) throw CancellationException()
            }
        }
    }
}
