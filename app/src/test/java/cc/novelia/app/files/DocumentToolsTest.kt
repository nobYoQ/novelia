package cc.novelia.app.files

import cc.novelia.app.data.model.navigationToc
import cc.novelia.app.data.model.readingOrderToc
import cc.novelia.app.reader.ReadingParagraph
import cc.novelia.app.reader.TextPart
import cc.novelia.app.reader.chapterHeading
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentToolsTest {
    @get:Rule val folder = TemporaryFolder()

    private fun archive(files: Map<String, String>, utf16: Set<String> = emptySet()): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(if (name in utf16) Charsets.UTF_16 else Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }.toByteArray()
    @Test fun epubUsesSpineOrderRatherThanArchiveOrder() {
        val bytes = archive(linkedMapOf(
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OEBPS/book.opf"/></rootfiles></container>""",
            "OEBPS/second.xhtml" to "<html><body><h1>第二章</h1><p>第二段正文。</p></body></html>",
            "OEBPS/first.xhtml" to "<html><body><h1>第一章</h1><p>第一段正文。</p></body></html>",
            "OEBPS/book.opf" to """<package><manifest><item id="one" href="first.xhtml"/><item id="two" href="second.xhtml"/></manifest><spine><itemref idref="one"/><itemref idref="two"/></spine></package>"""
        ))
        val doc = DocumentTools.parse("测试.epub", bytes)
        assertEquals(listOf("第一章", "第二章"), doc.chapters.map { it.title })
        assertTrue(doc.chapters.first().paragraphs.contains("第一段正文。"))
    }
    @Test fun rejectsArchiveTraversal() {
        val bytes = archive(mapOf("../../escape" to "unexpected"))
        assertThrows(IllegalArgumentException::class.java) { DocumentTools.unzip(bytes) }
    }
    @Test fun epubUsesNavAndNcxLabelsAndFallsBackToTheBodyHeading() {
        val bytes = archive(mapOf(
            "META-INF/container.xml" to "<container><rootfile full-path='OPS/book.opf'/></container>",
            "OPS/book.opf" to """
                <package xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <metadata><dc:title>卷名</dc:title></metadata>
                  <manifest>
                    <item id="nav" href="nav/toc.xhtml" properties="nav"/>
                    <item id="ncx" href="nav/book.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="one" href="text/第%201%2B章.xhtml"/><item id="two" href="text/two.xhtml"/>
                    <item id="three" href="text/three.xhtml"/><item id="four" href="text/four.xhtml"/>
                  </manifest>
                  <spine toc="ncx"><itemref idref="one"/><itemref idref="two"/><itemref idref="three"/><itemref idref="four"/></spine>
                </package>
            """.trimIndent(),
            "OPS/nav/toc.xhtml" to """
                <?xml version="1.0" encoding="UTF-16"?>
                <html xmlns:epub="http://www.idpf.org/2007/ops"><body>
                  <nav epub:type="landmarks"><ol><li><a href="../text/two.xhtml">路标</a></li></ol></nav>
                  <nav epub:type="toc"><ol>
                    <li><a href="https://example.test/text/two.xhtml">外链</a></li>
                    <li><a href="../text/%ZZ.xhtml">无效链接</a></li>
                    <li><a href="../text/第%201%2B章.xhtml#chapter">第一章</a>
                      <ol><li><a href="../text/第%201%2B章.xhtml#section">小节</a></li></ol>
                    </li>
                  </ol></nav>
                </body></html>
            """.trimIndent(),
            "OPS/nav/book.ncx" to """
                <ncx><navMap>
                  <navPoint><navLabel><text>第二章</text></navLabel><content src="../text/two.xhtml#chapter"/></navPoint>
                  <navPoint><navLabel><text>旧目录章名</text></navLabel><content src="../text/第%201%2B章.xhtml"/></navPoint>
                </navMap></ncx>
            """.trimIndent(),
            "OPS/text/第 1+章.xhtml" to "<html><head><title>卷名</title></head><body><h1>正文小标题</h1><p>正文</p></body></html>",
            "OPS/text/two.xhtml" to "<html><head><title>卷名</title></head><body><p id='chapter'>第二章的正文</p></body></html>",
            "OPS/text/three.xhtml" to "<?xml version='1.0' encoding='UTF-16'?><html><head><title>卷名</title></head><body><h2>第三章</h2><p>第三章的正文</p></body></html>",
            "OPS/text/four.xhtml" to "<html><head><title>卷名</title></head><body><p>附录的正文</p></body></html>",
        ), utf16 = setOf("OPS/nav/toc.xhtml", "OPS/text/three.xhtml"))
        val file = folder.newFile("chapter-titles.epub").apply { writeBytes(bytes) }
        val disk = DocumentTools.parseFile(file.name, file, { _, _ -> })
        val memory = DocumentTools.parse(file.name, bytes)

        assertEquals(listOf("one", "two", "three", "four"), disk.chapters.map { it.id })
        assertEquals(listOf("one"), disk.epubNavigationIds)
        assertEquals(disk.epubNavigationIds, memory.epubNavigationIds)
        assertEquals(listOf("第一章", "第二章", "第三章", "章节"), disk.chapters.map { it.title })
        assertEquals(listOf("正文小标题", "正文"), disk.chapters[0].paragraphs)
        assertEquals(listOf("第三章", "第三章的正文"), disk.chapters[2].paragraphs)
        assertEquals(disk.chapters, memory.chapters)
    }

    @Test fun epub2PublishesOnlyNcxEntriesWhileRetainingUnlistedSpineContent() {
        val bytes = archive(mapOf(
            "META-INF/container.xml" to "<container><rootfile full-path='book.opf'/></container>",
            "book.opf" to """<package version="2.0"><manifest>
                <item id="first" href="first.xhtml"/><item id="c1S" href="c1S.xhtml"/>
                <item id="last" href="last.xhtml"/><item id="ncx" href="toc.ncx"/>
                </manifest><spine toc="ncx"><itemref idref="first"/><itemref idref="c1S"/><itemref idref="last"/></spine></package>""",
            "toc.ncx" to """<ncx><navMap>
                <navPoint><navLabel><text>尾声</text></navLabel><content src="last.xhtml"/></navPoint>
                <navPoint><navLabel><text>序章</text></navLabel><content src="first.xhtml#s"/></navPoint>
                </navMap></ncx>""",
            "first.xhtml" to "<p id='s'>开头</p>",
            "c1S.xhtml" to "<html><head><title>c1S</title></head><body><p>接着读的正文</p></body></html>",
            "last.xhtml" to "<p>结尾</p>",
        ))
        val file = folder.newFile("navigation.epub").apply { writeBytes(bytes) }
        val doc = DocumentTools.parseFile(file.name, file, { _, _ -> })

        assertEquals(listOf("first", "c1S", "last"), doc.readingOrderToc().map { it.chapterId })
        assertEquals(listOf("尾声", "序章"), doc.navigationToc().map { it.title })
        val continuation = doc.chapters[1]
        assertEquals(listOf("接着读的正文"), continuation.paragraphs)
        assertNull(chapterHeading(continuation.title, listOf(ReadingParagraph(0, listOf(TextPart(continuation.paragraphs.single(), "local"))))))
        assertEquals(doc.navigationToc(), DocumentTools.parse(file.name, bytes).navigationToc())
        assertEquals(doc.readingOrderToc(), doc.copy(epubNavigationIds = emptyList()).navigationToc())
    }
    @Test fun epubIllustrationsRemainInReadingOrder() {
        val bytes = archive(linkedMapOf(
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles></container>""",
            "OPS/book.opf" to """<package><manifest><item id="c" href="c.xhtml"/><item id="cover" href="image.png" properties="cover-image"/></manifest><spine><itemref idref="c"/></spine></package>""",
            "OPS/c.xhtml" to "<html><body><p>插图之前</p><img src='image.png'/><p>插图之后</p></body></html>",
            "OPS/image.png" to "image-bytes-for-parser-test"
        ))
        val doc = DocumentTools.parse("插图.epub", bytes)
        assertEquals(1, doc.images.size)
        assertNotNull(doc.coverImage)
        assertEquals("插图之前", doc.chapters.single().paragraphs[0])
        assertTrue(doc.chapters.single().paragraphs[1].startsWith("novelia-image:"))
        assertEquals("插图之后", doc.chapters.single().paragraphs[2])
        assertFalse(DocumentTools.epubToTxt(bytes).contains("novelia-image:"))
    }
    @Test fun srtKeepsTimestampsAndSequenceNumbers() {
        val text = "1\r\n00:00:01,000 --> 00:00:03,000\r\n你好\r\n\r\n2\r\n00:00:04,000 --> 00:00:05,000\r\n世界"
        val doc = DocumentTools.parse("字幕.srt", text.toByteArray())
        assertEquals(2, doc.chapters.single().paragraphs.size)
        assertTrue(doc.chapters.single().paragraphs[0].contains("00:00:01,000 --> 00:00:03,000"))
    }
    @Test fun recognizesChineseEncodingWithoutCorruptingSource() {
        val text = "第一章 开始\n这是中文正文。"
        assertEquals(text, DocumentTools.decodeText(text.toByteArray(Charsets.UTF_16LE).let { byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + it }))
        assertEquals(text, DocumentTools.decodeText(text.toByteArray(java.nio.charset.Charset.forName("GB18030"))))
    }
    @Test fun ocrRepairKeepsSentenceAndParagraphBoundaries() {
        assertEquals("一行文字。\n第二句\n\n新段落", DocumentTools.repairOcr("一行\n文字。\n第二句\n\n新段落"))
    }
    @Test fun katakanaCountsWholeTerms() {
        assertEquals("アリス" to 3, DocumentTools.katakana("アリスとボブ。アリス、アリス").first())
    }
}
