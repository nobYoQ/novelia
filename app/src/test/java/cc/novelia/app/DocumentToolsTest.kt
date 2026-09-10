package cc.novelia.app

import cc.novelia.app.files.DocumentTools
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentToolsTest {
    private fun archive(files: Map<String, String>): ByteArray = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip -> files.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() } } }.toByteArray()
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
