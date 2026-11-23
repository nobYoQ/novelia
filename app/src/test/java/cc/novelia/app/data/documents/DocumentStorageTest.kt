package cc.novelia.app.data.documents

import cc.novelia.app.data.model.LocalChapter
import cc.novelia.app.data.model.LocalDocument
import cc.novelia.app.data.model.LocalBilingualGroup
import cc.novelia.app.data.model.LocalReadingContent
import cc.novelia.app.data.model.UNAVAILABLE_EPUB_CHAPTER_TEXT
import cc.novelia.app.data.model.displayChapterCount
import cc.novelia.app.data.model.navigationToc
import cc.novelia.app.data.storage.appJson
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class DocumentStorageTest {
    private val original = LocalDocument("book", "中文书名", "txt", listOf(
        LocalChapter("chapter/1", "第一章", listOf("正文甲")),
        LocalChapter("chapter/2", "第二章", listOf("正文乙"))), sourceHash = "source")

    @Test fun epubDisplayCountUsesTheOriginalDirectoryAndIgnoresIllustrationOnlyChapters() {
        val directory = Files.createTempDirectory("document-chapter-count").toFile()
        try {
            val reads = mutableListOf<String>()
            val storage = DocumentStorage(directory, { reads += it.name; it.readText() }, { file, text -> file.writeText(text) })
            val image = "novelia-image:${"a".repeat(64)}"
            val document = original.copy(format = "epub", chapters = listOf(
                LocalChapter("cover", "封面", listOf(image)),
                LocalChapter("art", "彩色插图", listOf("彩色插图", image)),
                LocalChapter("unavailable", "图版", listOf(UNAVAILABLE_EPUB_CHAPTER_TEXT)),
                LocalChapter("first", "第一章", listOf("第一章", image, "混有插图的正文")),
                LocalChapter("continuation", "续页", listOf("第一章的未列出续页")),
                LocalChapter("last", "末章", listOf("末章的正文")),
            ), epubNavigationIds = listOf("art", "first", "last"))
            assertEquals(2, document.displayChapterCount())
            assertEquals(3, document.copy(epubNavigationIds = emptyList()).displayChapterCount())
            val restored = storage.index(storage.save(document).id)
            reads.clear()
            assertEquals(2, storage.displayChapterCount(restored) {})
            assertEquals(setOf("art", "first", "last").map { "${restored.chapterFiles[it]}.json" }.toSet(), reads.toSet())
            assertEquals(document.chapters, storage.full(restored).chapters)
            assertEquals(2, original.displayChapterCount())
        } finally { directory.deleteRecursively() }
    }

    @Test fun retainedDocumentIdsExcludeIndexesAndUncommittedFilesWithoutReadingBodies() {
        val directory = Files.createTempDirectory("retained-documents").toFile()
        try {
            listOf("book.json", "book.json.bak", "retained.json.bak", "source-index.json", "source-index.json.bak",
                "unfinished.json.new", "bad_id.json", "source.txt").forEach { File(directory, it).writeText("{}", Charsets.UTF_8) }
            File(directory, "nested-chapters").mkdirs()
            File(directory, "nested-chapters/chapter.json").writeText("{}", Charsets.UTF_8)
            val storage = DocumentStorage(directory, { error("Enumeration must not read document contents") }, { _, _ -> error("Must not write") })
            assertEquals(listOf("book", "retained"), storage.ids())
        } finally { directory.deleteRecursively() }
    }

    @Test fun bilingualMetadataStaysInChapterFilesAndAnInterruptedUpgradeKeepsTheOldChapter() {
        val directory = Files.createTempDirectory("bilingual-documents").toFile()
        try {
            var failManifest = false
            val storage = DocumentStorage(directory, { it.readText() }, { file, text ->
                if (failManifest && file.name == "book.json") throw java.io.IOException("disk full")
                file.writeText(text)
            })
            val chapter = LocalChapter("c", "章", listOf("日本語", "中文"))
            val index = storage.save(LocalDocument("book", "卷", "epub", listOf(chapter), downloadMode = "jp-zh"))
            val upgraded = chapter.copy(readingContent = LocalReadingContent(listOf(LocalBilingualGroup(listOf(0), listOf(listOf(1)))), listOf(0)), epubContentVersion = 1)
            failManifest = true
            assertThrows(java.io.IOException::class.java) { storage.replaceChapter(index, upgraded) }
            assertEquals(chapter, storage.chapter(storage.index("book"), "c"))
            failManifest = false
            val next = storage.replaceChapter(index, upgraded)
            assertTrue(next.chapters.single().readingContent.groups.isEmpty())
            assertEquals(upgraded, storage.chapter(next, "c"))
            assertEquals(upgraded, storage.full(storage.index("book")).chapters.single())
            assertEquals("jp-zh", storage.full(next).downloadMode)
        } finally { directory.deleteRecursively() }
    }

    @Test fun epubTitleUpgradeUpdatesTheEntireIndexAtomicallyWithoutRewritingChapterFiles() {
        val directory = Files.createTempDirectory("epub-title-upgrade").toFile()
        try {
            val source = File(directory, "book.epub")
            ZipOutputStream(source.outputStream()).use { zip ->
                mapOf(
                    "META-INF/container.xml" to "<container><rootfile full-path='book.opf'/></container>",
                    "book.opf" to """<package><manifest>
                        <item id="chapter/1" href="one.xhtml"/><item id="chapter/2" href="two.xhtml"/><item id="ncx" href="toc.ncx"/>
                        </manifest><spine toc="ncx"><itemref idref="chapter/1"/><itemref idref="chapter/2"/></spine></package>""",
                    "toc.ncx" to """<ncx><navMap><navPoint><navLabel><text>第一章</text></navLabel>
                        <content src="one.xhtml"/></navPoint></navMap></ncx>""",
                    "one.xhtml" to "<html><head><title>卷名</title></head><body><h1>第一章</h1><p>正文甲</p></body></html>",
                    "two.xhtml" to "<html><head><title>卷名</title></head><body><h1>第二章</h1><p>正文乙</p></body></html>",
                ).forEach { (name, text) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
            val writes = mutableListOf<String>()
            var failManifest = false
            val storage = DocumentStorage(directory, { it.readText() }, { file, text ->
                if (failManifest && file.name == "book.json") throw java.io.IOException("disk full")
                writes += file.name
                file.writeText(text)
            })
            val legacy = original.copy(format = "epub", chapters = original.chapters.map { it.copy(title = "卷名") }, epubTitleVersion = 1)
            val index = storage.save(legacy)
            writes.clear()
            failManifest = true
            assertThrows(java.io.IOException::class.java) { storage.recoverEpubTitles(index, source) }
            assertEquals(legacy, storage.full(storage.index("book")))

            failManifest = false
            val upgraded = storage.recoverEpubTitles(index, source)
            assertEquals(listOf("book.json"), writes)
            assertEquals(index.chapterFiles, upgraded.chapterFiles)
            assertEquals(upgraded, storage.index("book"))
            assertEquals(listOf("chapter/1"), upgraded.navigationToc().map { it.chapterId })
            assertEquals(original.chapters, storage.full(upgraded).chapters)
            assertSame(upgraded, storage.recoverEpubTitles(upgraded, source))
            assertEquals(listOf("book.json"), writes)
        } finally { directory.deleteRecursively() }
    }

    @Test fun chapterReadsDoNotLoadOtherChaptersAndPortableExportRoundTrips() {
        val directory = Files.createTempDirectory("documents").toFile()
        try {
            val reads = mutableListOf<String>()
            val storage = DocumentStorage(directory, { reads += it.name; it.readText() }, { file, text -> file.writeText(text) })
            val index = storage.save(original)
            assertTrue(index.chapters.all { it.paragraphs.isEmpty() })
            assertEquals(original.chapters.first(), storage.chapter(index, "chapter/1"))
            assertEquals(listOf("${index.chapterFiles["chapter/1"]}.json"), reads)
            assertEquals(original, storage.full(storage.index("book")))
            storage.remove("book")
            assertFalse(File(directory, "book-chapters").exists())
            assertFalse(File(directory, "book.json").exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun legacyImportMigratesOnlyOnceAndFailedReplacementKeepsCommittedBook() {
        val directory = Files.createTempDirectory("documents").toFile()
        try {
            File(directory, "book.json").writeText(appJson.encodeToString(original))
            var writes = 0
            var failManifest = false
            val storage = DocumentStorage(directory, { it.readText() }, { file, text ->
                if (failManifest && file.name == "book.json") error("disk full")
                writes++; file.writeText(text)
            })
            val index = storage.index("book")
            val migratedWrites = writes
            assertEquals(index, storage.index("book"))
            assertEquals(migratedWrites, writes)
            failManifest = true
            val changed = original.copy(chapters = listOf(original.chapters.first().copy(paragraphs = listOf("新正文"))))
            assertTrue(runCatching { storage.save(changed) }.isFailure)
            assertEquals(original, storage.full(storage.index("book")))
        } finally { directory.deleteRecursively() }
    }

    @Test fun cancelledMigrationAndUnsafeOrCorruptReferencesCannotReplaceTheSource() {
        val directory = Files.createTempDirectory("documents").toFile()
        try {
            val source = File(directory, "book.json").apply { writeText(appJson.encodeToString(original)) }
            val storage = DocumentStorage(directory, { it.readText() }, { file, text -> file.writeText(text) })
            var count = 0
            assertTrue(runCatching { storage.save(original) { if (++count == 2) error("cancelled") } }.isFailure)
            assertEquals(original, appJson.decodeFromString<LocalDocument>(source.readText()))
            val index = storage.index("book")
            assertTrue(runCatching { storage.chapter(index.copy(chapterFiles = mapOf("chapter/1" to "../outside")), "chapter/1") }.isFailure)
            File(directory, "book-chapters/${index.chapterFiles["chapter/1"]}.json").writeText(appJson.encodeToString(original.chapters.first().copy(paragraphs = listOf("damaged"))))
            assertTrue(runCatching { storage.chapter(index, "chapter/1") }.isFailure)
        } finally { directory.deleteRecursively() }
    }

    @Test fun diskShortageDuringLegacyMigrationKeepsTheBookReadable() {
        val directory = Files.createTempDirectory("documents").toFile()
        try {
            File(directory, "book.json").writeText(appJson.encodeToString(original))
            val storage = DocumentStorage(directory, { it.readText() }, { _, _ -> throw java.io.IOException("disk full") })
            val index = storage.index("book")
            assertEquals(original.chapters.first(), storage.chapter(index, "chapter/1"))
            assertEquals(original, storage.full(index))
        } finally { directory.deleteRecursively() }
    }

    @Test fun renamingOnlyWritesTheIndexAndPreservesChaptersAndDeduplicationHash() {
        val directory = Files.createTempDirectory("rename-document").toFile()
        try {
            val writes = mutableListOf<String>()
            val storage = DocumentStorage(directory, { it.readText() }, { file, text -> writes += file.name; file.writeText(text) })
            val index = storage.save(original)
            writes.clear()
            val renamed = storage.rename(index, "  新卷名  ")
            assertEquals(listOf("book.json"), writes)
            assertEquals(index.copy(name = "新卷名"), renamed)
            assertEquals(original.copy(name = "新卷名"), storage.full(storage.index("book")))
            assertThrows(IllegalArgumentException::class.java) { storage.rename(renamed, "  ") }
            assertEquals(renamed, storage.index("book"))
        } finally { directory.deleteRecursively() }
    }
}
