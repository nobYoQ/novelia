package cc.novelia.app.files

import cc.novelia.app.data.model.*
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.files.epub.parseEpubChapter
import cc.novelia.app.files.epub.recoverEpubChapter
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EpubInlineTest {
    @get:Rule val folder = TemporaryFolder()
    private fun parse(body: String) = parseEpubChapter("c", body, null, { null })!!
    private fun marked(chapter: LocalChapter, kind: InlineKind) = chapter.readingContent.inline.flatMap { (index, marks) ->
        marks.filter { it.kind == kind }.map { chapter.paragraphs[index].substring(it.start, it.end) to it.value }
    }

    @Test fun rubyKeepsBaseTextAndSupportsWordAndPairedForms() {
        val chapter = parse("<p><ruby>東京<rp>(</rp><rt>とうきょう</rt><rp>)</rp></ruby>へ" +
            "<ruby>漢<rt>かん</rt>字<rt>じ</rt></ruby>と<ruby><rb>日</rb><rb>本</rb><rt>に</rt><rt>ほん</rt></ruby></p>")
        assertEquals(listOf("東京へ漢字と日本"), chapter.paragraphs)
        assertEquals(listOf("東京" to "とうきょう", "漢" to "かん", "字" to "じ", "日" to "に", "本" to "ほん"), marked(chapter, InlineKind.Ruby))
    }

    @Test fun whitespaceAndSurrogatesDoNotShiftInlineRanges() {
        val chapter = parse("<p> \n<ruby>\n 𠀀\n <rt>よみ</rt></ruby>\n <b>漢\n字</b> <em>文</em> </p>")
        assertEquals(listOf("𠀀漢字 文"), chapter.paragraphs)
        assertEquals(listOf("𠀀" to "よみ"), marked(chapter, InlineKind.Ruby))
        assertEquals(listOf("漢字" to ""), marked(chapter, InlineKind.Bold))
        assertEquals(listOf("文" to ""), marked(chapter, InlineKind.Italic))
    }

    @Test fun blockAndImageBoundariesSplitStylesWithoutChangingParagraphs() {
        val chapter = parseEpubChapter("c", "<p><b>甲<br/>乙<img src='x'/>丙</b></p><p style='font-style:italic;text-decoration:underline'>丁</p>", null, { "a".repeat(64) })!!
        assertEquals(listOf("甲", "乙", "novelia-image:" + "a".repeat(64), "丙", "丁"), chapter.paragraphs)
        assertEquals(listOf("甲", "乙", "丙"), marked(chapter, InlineKind.Bold).map { it.first })
        assertEquals(listOf("丁"), marked(chapter, InlineKind.Underline).map { it.first })
        assertFalse(chapter.readingContent.inline.containsKey(2))
    }

    private fun book(): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            mapOf(
                "META-INF/container.xml" to "<container><rootfile full-path='OPS/book.opf'/></container>",
                "OPS/book.opf" to "<package><manifest><item id='c' href='c.xhtml'/><item id='notes' href='notes.xhtml'/></manifest><spine><itemref idref='c'/></spine></package>",
                "OPS/c.xhtml" to "<p><ruby>東京<rt>とうきょう</rt></ruby><a epub:type='noteref' href='notes.xhtml#n%201'>[1]</a>" +
                    "<a href='#local'>[2]</a><a href='#ordinary'>普通链接</a><a epub:type='noteref' href='https://example.org/#n'>远程</a>" +
                    "<a epub:type='noteref' href='notes.xhtml#missing'>缺失</a></p>" +
                    "<aside id='local' epub:type='footnote'><p>本章说明</p></aside><p id='ordinary'>正文</p>",
                "OPS/notes.xhtml" to "<aside id='n 1' epub:type='footnote'><p>跨文件<b>注释</b><a epub:type='backlink' href='c.xhtml'>返回</a></p><script>bad()</script></aside>"
            ).forEach { (path, html) -> zip.putNextEntry(ZipEntry(path)); zip.write(html.toByteArray()); zip.closeEntry() }
        }
    }.toByteArray()

    @Test fun memoryAndFileImportsResolveStandardNotesOutsideSpine() {
        val bytes = book()
        val file = folder.newFile("book.epub").apply { writeBytes(bytes) }
        val chapter = DocumentTools.parse("book.epub", bytes).chapters.single()
        assertEquals(chapter, DocumentTools.parseFile("book.epub", file, { _, _ -> }).chapters.single())
        assertEquals(mapOf("OPS/notes.xhtml#n 1" to "跨文件注释", "OPS/c.xhtml#local" to "本章说明"), chapter.readingContent.notes)
        assertEquals(listOf("[1]", "[2]"), marked(chapter, InlineKind.Note).map { it.first })
        assertEquals(listOf("本章说明", "正文"), chapter.paragraphs.drop(1))
        assertEquals(chapter, appJson.decodeFromString<LocalChapter>(appJson.encodeToString(chapter)))
        val legacy = chapter.copy(readingContent = LocalReadingContent(), epubContentVersion = 2)
        assertEquals(chapter, recoverEpubChapter(file, legacy, null))
    }

    @Test fun nestedStylesAndPreformattedOffsetsRemainAttached() {
        val chapter = parse("<pre>  a\n <b>b <i>c</i></b>  </pre>")
        assertEquals(listOf("a\n b c"), chapter.paragraphs)
        assertEquals(listOf("b c" to ""), marked(chapter, InlineKind.Bold))
        assertEquals(listOf("c" to ""), marked(chapter, InlineKind.Italic))
    }
}
