package cc.novelia.app.files

import cc.novelia.app.data.model.EpubLinkTarget
import cc.novelia.app.data.model.EpubNavigationEntry
import cc.novelia.app.data.model.LocalReadingContent
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.data.model.toReaderChapter
import cc.novelia.app.data.model.navigationToc
import cc.novelia.app.data.model.navigationWithUnlisted
import cc.novelia.app.data.model.readingOrderToc
import cc.novelia.app.data.model.reverseTocSiblings
import cc.novelia.app.reader.ReadingParagraph
import cc.novelia.app.reader.TextPart
import cc.novelia.app.reader.chapterHeading
import cc.novelia.app.reader.projectParagraphs
import cc.novelia.app.files.epub.recoverEpubChapter
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
        assertEquals(listOf(
            EpubNavigationEntry("第一章", EpubLinkTarget("one", "chapter"), 0),
            EpubNavigationEntry("小节", EpubLinkTarget("one", "section"), 1),
        ), disk.epubNavigation)
        assertEquals(disk.epubNavigation, memory.epubNavigation)
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
        assertEquals(doc.readingOrderToc(), doc.copy(epubNavigationIds = emptyList(), epubNavigation = emptyList()).navigationToc())
    }

    @Test fun prefixedEpubNavKeepsGroupsAndFragmentOnlyTargetsInTheNativeToc() {
        val bytes = archive(mapOf(
            "META-INF/container.xml" to """<ocf:container xmlns:ocf="urn:oasis:names:tc:opendocument:xmlns:container">
                <ocf:rootfiles><ocf:rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></ocf:rootfiles>
                </ocf:container>""",
            "OPS/book.opf" to """<p:package xmlns:p="http://www.idpf.org/2007/opf" xmlns:d="http://purl.org/dc/elements/1.1/" version="3.0">
                <p:metadata><d:title>全书名</d:title></p:metadata>
                <p:manifest>
                  <p:item id="contents" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                  <p:item id="illustration" href="x1.xhtml" media-type="application/xhtml+xml"/>
                </p:manifest>
                <p:spine><p:itemref idref="contents"/><p:itemref idref="illustration"/></p:spine>
                </p:package>""",
            "OPS/nav.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:kind="http://www.idpf.org/2007/ops">
                <body><nav kind:type="toc"><ol>
                  <li><span>第一部</span><ol>
                    <li><a href="#one">第一节</a></li>
                    <li><a href="nav.xhtml#%E4%BA%8C">第二节</a></li>
                  </ol></li>
                </ol></nav><p id="one">第一节正文</p><p id="二">第二节正文</p></body></html>""",
            "OPS/x1.xhtml" to "<html><head><title>全书名</title></head><body><p>未列出的内容</p></body></html>",
        ))
        val file = folder.newFile("prefixed.epub").apply { writeBytes(bytes) }
        val disk = DocumentTools.parseFile(file.name, file, { _, _ -> })
        val memory = DocumentTools.parse(file.name, bytes)
        assertEquals(disk.chapters, memory.chapters)
        assertEquals(disk.epubNavigation, memory.epubNavigation)
        assertEquals(listOf("第一节", "章节"), disk.chapters.map { it.title })
        assertEquals(listOf("第一部", "第一节", "第二节"), disk.navigationToc().map { it.title })
        assertEquals(listOf(0, 1, 1), disk.navigationToc().map { it.depth })
        assertEquals(listOf(null, "one", "二"), disk.navigationToc().map { it.epubFragment })
        val reversed = disk.navigationToc().withIndex().toList().reverseTocSiblings()
        assertEquals(listOf("第一部", "第二节", "第一节"), reversed.map { it.value.title })
        val chapter = disk.chapters.first()
        for ((fragment, body) in listOf("one" to "第一节正文", "二" to "第二节正文")) {
            assertEquals(body, chapter.paragraphs[chapter.readingContent.anchors.getValue(fragment)])
        }
        val extras = disk.navigationWithUnlisted(disk.readingOrderToc(), disk.navigationToc())
        assertEquals(listOf("第一部", "第一节", "第二节", "章节"), extras.map { it.title })
        assertEquals(listOf(null, "contents", "contents", "illustration"), extras.map { it.chapterId })
    }

    @Test fun prefixedNcxPreservesNestedSectionsWithinOneChapter() {
        val bytes = archive(mapOf(
            "META-INF/container.xml" to "<container><rootfile full-path='book.opf'/></container>",
            "book.opf" to """<package version="2.0"><manifest>
                <item id="body" href="body.xhtml" media-type="application/xhtml+xml"/>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                </manifest><spine toc="ncx"><itemref idref="body"/></spine></package>""",
            "toc.ncx" to """<n:ncx xmlns:n="http://www.daisy.org/z3986/2005/ncx/"><n:navMap><n:navPoint>
                <n:navLabel><n:text>第一章</n:text></n:navLabel><n:content src="body.xhtml"/>
                <n:navPoint><n:navLabel><n:text>小节</n:text></n:navLabel><n:content src="body.xhtml#section"/></n:navPoint>
                </n:navPoint></n:navMap></n:ncx>""",
            "body.xhtml" to """<p>正文</p><h3 id="section">小节标题</h3>""",
        ))
        val document = DocumentTools.parse("ncx.epub", bytes)
        assertEquals(listOf(
            EpubNavigationEntry("第一章", EpubLinkTarget("body"), 0),
            EpubNavigationEntry("小节", EpubLinkTarget("body", "section"), 1),
        ), document.epubNavigation)
        assertEquals(listOf("", "section"), document.navigationToc().map { it.epubFragment })
    }

    @Test fun repeatedSpineOccurrencesAndFallbackChainsImportTheSameWayFromMemoryAndDisk() {
        val bytes = archive(mapOf(
            "META-INF/container.xml" to "<container><rootfile full-path='book.opf'/></container>",
            "book.opf" to """<package><manifest>
                <item id="foreign" href="foreign.xhtml" media-type="application/example" fallback="missing"/>
                <item id="missing" href="missing.xhtml" media-type="application/xhtml+xml" fallback="fallback"/>
                <item id="fallback" href="text/fallback.xhtml" media-type="application/xhtml+xml"/>
                <item id="cycle" href="cycle.svg" media-type="image/svg+xml" fallback="other"/>
                <item id="other" href="other.svg" media-type="image/svg+xml" fallback="cycle"/>
                <item id="body" href="body.xhtml" media-type="application/xhtml+xml"/>
                </manifest><spine><itemref idref="foreign" linear="no"/><itemref idref="body"/>
                <itemref idref="foreign"/><itemref idref="cycle"/><itemref idref="foreign"/></spine></package>""",
            "foreign.xhtml" to "<p>不能把这种资源当作正文。</p>",
            "text/fallback.xhtml" to "<p><a href='#answer'>同页定位</a></p><p id='answer'>替代正文</p>",
            "cycle.svg" to "<svg><text>此资源无法排版</text></svg>",
            "body.xhtml" to "<p><a href='foreign.xhtml#answer'>跳往替代正文</a> <a href='text/fallback.xhtml#answer'>直接定位</a></p>",
        ))
        val file = folder.newFile("fallback.epub").apply { writeBytes(bytes) }
        val disk = DocumentTools.parseFile(file.name, file, { _, _ -> })
        val memory = DocumentTools.parse(file.name, bytes)
        assertEquals(disk.chapters, memory.chapters)
        assertEquals(listOf("foreign", "body", "foreign~2", "cycle", "foreign~3"), disk.chapters.map { it.id })
        for (index in listOf(0, 2, 4)) {
            val chapter = disk.chapters[index]
            assertEquals(listOf("同页定位", "替代正文"), chapter.paragraphs)
            assertEquals(EpubLinkTarget("foreign~2", "answer"), chapter.readingContent.links.values.single())
        }
        assertEquals(List(2) { EpubLinkTarget("foreign~2", "answer") }, disk.chapters[1].readingContent.links.values.toList())
        assertEquals("无法显示此 EPUB 章节", disk.chapters[3].title)
        assertTrue(disk.chapters[3].paragraphs.single().contains("没有可读取的替代内容"))
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

    @Test fun epubHidesInlineHiddenContentSeparatesTableCellsAndPreservesImageAlt() {
        val bytes = archive(mapOf(
            "META-INF/container.xml" to "<container><rootfile full-path='book.opf'/></container>",
            "book.opf" to """<package><manifest><item id="c" href="c.xhtml"/></manifest>
                <spine page-progression-direction="rtl"><itemref idref="c"/></spine></package>""",
            "c.xhtml" to """<h1 hidden>隠れた見出し</h1><p>本文</p>
                <p style="DISPLAY: none ! important; display: block">隠れた内容</p>
                <p style="display:none;display:block">見える</p>
                <table><tr><th>動物</th><td>猫</td><td>犬</td></tr></table>
                <img src="picture.png" alt="窓辺にいる猫"/>""",
            "picture.png" to "image used for parsing",
        ))
        val source = folder.newFile("reading.epub").apply { writeBytes(bytes) }
        val chapter = DocumentTools.parseFile(source.name, source, { _, _ -> }).chapters.single()
        assertEquals(chapter, DocumentTools.parse(source.name, bytes).chapters.single())
        assertEquals("章节", chapter.title)
        assertEquals(listOf("本文", "見える", "動物", "猫", "犬"), chapter.paragraphs.dropLast(1))
        val image = chapter.paragraphs.last()
        assertTrue(image.startsWith("novelia-image:"))
        assertEquals(mapOf(5 to "窓辺にいる猫"), chapter.readingContent.imageDescriptions)
        val paragraph = projectParagraphs(chapter.toReaderChapter(), ReaderSettings()).last()
        assertEquals("窓辺にいる猫", paragraph.imageDescription)

        val legacy = chapter.copy(paragraphs = listOf("隠れた見出し", "本文", "隠れた内容", "見える", "動物猫犬", image),
            readingContent = LocalReadingContent(), epubContentVersion = 5)
        val recovered = recoverEpubChapter(source, legacy, null)
        assertEquals(legacy.paragraphs, recovered.paragraphs)
        assertEquals(mapOf(5 to "窓辺にいる猫"), recovered.readingContent.imageDescriptions)
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
