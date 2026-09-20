package cc.novelia.app.data

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DocumentStorageTest {
    private val original = LocalDocument("book", "中文书名", "txt", listOf(
        LocalChapter("chapter/1", "第一章", listOf("正文甲")),
        LocalChapter("chapter/2", "第二章", listOf("正文乙"))), sourceHash = "source")

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
}
