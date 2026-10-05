package cc.novelia.app.data.backup

import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.DownloadEntry
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.LocalChapter
import cc.novelia.app.data.model.LocalDocument
import cc.novelia.app.data.model.Note
import cc.novelia.app.data.model.PendingAction
import cc.novelia.app.data.model.Position
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.data.model.ReadingHistoryEntry
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.storage.loadLibraryState
import cc.novelia.app.data.webdav.SyncReplica
import cc.novelia.app.data.webdav.WebDavProjection
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class LibraryBackupTest {
    private fun temp() = Files.createTempDirectory("novelia-backup-test-").toFile()
    private fun archive(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
    }.toByteArray()

    private fun manifest(state: LibraryState = LibraryState(), version: Int = 1, assets: Map<String, BackupAsset> = emptyMap(), documents: List<String> = emptyList()) =
        LibraryBackupManifest("novelia-library", version, 1, state, documents, assets = assets)

    private fun fails(block: () -> Unit) {
        try { block(); fail("expected backup validation failure") }
        catch (expected: IllegalArgumentException) { /* 拒绝恢复时不改动当前书库。 */ }
    }

    @Test fun lineHeightBelowOneRestoresForGlobalAndPerBookSettingsButInvalidValuesAreRejected() {
        val root = temp()
        try {
            for(multiplier in listOf(.5f, .8f, 4f, .49f, 4.1f)) {
                for(perBook in listOf(false, true)) {
                    val settings = ReaderSettings(lineHeight = multiplier)
                    val state = if(perBook) LibraryState(bookSettings = mapOf("syosetu/n1" to settings)) else LibraryState(reader = settings)
                    val bytes = archive(mapOf("manifest.json" to appJson.encodeToString(manifest(state)).toByteArray()))
                    val stage = File(root, "$multiplier-$perBook").apply { mkdirs() }
                    if(multiplier in .5f..4f) {
                        assertEquals(state, LibraryBackupArchive.extract(ByteArrayInputStream(bytes), stage).library)
                    } else fails { LibraryBackupArchive.extract(ByteArrayInputStream(bytes), stage) }
                }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun keywordCapacityPreferenceSurvivesBackupAndRejectsInvalidLimits() {
        val root = temp()
        try {
            for(limit in listOf(null, 30_000, 0, -1)) {
                val state = LibraryState(keywordLimit = limit)
                val bytes = archive(mapOf("manifest.json" to appJson.encodeToString(manifest(state)).toByteArray()))
                val stage = File(root, "limit-$limit").apply { mkdirs() }
                if(limit == null || limit > 0) assertEquals(state, LibraryBackupArchive.extract(ByteArrayInputStream(bytes), stage).library)
                else fails { LibraryBackupArchive.extract(ByteArrayInputStream(bytes), stage) }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun unsafeZipPathsAreRejectedBeforeWritingOutsideStage() {
        val root = temp()
        try {
            listOf("../library.json", "/library.json", "documents/../../library.json", "documents\\book.json", "C:/library.json", "documents/book-images/../../outside").forEachIndexed { index, path ->
                val stage = File(root, "stage-$index").apply { mkdirs() }
                fails { LibraryBackupArchive.extract(ByteArrayInputStream(archive(mapOf(path to byteArrayOf(1)))), stage) }
            }
            assertFalse(File(root, "library.json").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun unsupportedVersionAndMismatchedAssetIndexAreRejected() {
        val root = temp()
        try {
            val unsupported = archive(mapOf("manifest.json" to appJson.encodeToString(manifest(version = 99)).toByteArray()))
            fails { LibraryBackupArchive.extract(ByteArrayInputStream(unsupported), File(root, "version").apply { mkdirs() }) }
            val extra = archive(mapOf("manifest.json" to appJson.encodeToString(manifest()).toByteArray(), "documents/unindexed.txt" to byteArrayOf(1)))
            fails { LibraryBackupArchive.extract(ByteArrayInputStream(extra), File(root, "extra").apply { mkdirs() }) }
        } finally { root.deleteRecursively() }
    }

    @Test fun failedIntegrityCheckDoesNotTouchExistingLibraryOrDocuments() {
        val root = temp()
        try {
            val existing = File(root, "library.json").apply { writeText("valuable current state") }
            val doc = File(root, "original.json").apply { writeText("original reading copy") }
            val manifest = manifest(assets = mapOf("documents/book.json" to LibraryBackupArchive.digest(doc)), documents = listOf("book"))
            val bytes = archive(mapOf("manifest.json" to appJson.encodeToString(manifest).toByteArray(), "documents/book.json" to "corrupt".toByteArray()))
            fails { LibraryBackupArchive.extract(ByteArrayInputStream(bytes), File(root, "stage").apply { mkdirs() }) }
            assertEquals("valuable current state", existing.readText())
            assertEquals("original reading copy", doc.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun parsedTextAndIllustrationsRoundTripWithoutOriginalEpubAndKeepRelationships() {
        val root = temp()
        try {
            val imageHash = "a".repeat(64)
            val document = LocalDocument("volume", "卷一", "epub", listOf(LocalChapter("chapter-1", "第一章", listOf("正文", "novelia-image:$imageHash"))), coverImage = imageHash)
            val json = File(root, "volume.json").apply { writeText(appJson.encodeToString(document)) }
            val image = File(root, "image").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val files = mapOf("documents/volume.json" to json, "documents/volume-images/$imageHash" to image)
            val local = BookRef("local", "volume"); val parent = BookRef("wenku", "series")
            val state = LibraryState(
                books = listOf(SavedBook(BookCard(parent, "系列"), volumeOrder = listOf(local.key)), SavedBook(BookCard(local, "卷一"), parentWenkuKey = parent.key)),
                positions = mapOf(local.key to Position("chapter-1", index = 1, textOffset = 2)),
                readingHistory = mapOf(local.key to ReadingHistoryEntry(local.key, "卷一", "chapter-1", "第一章", 123)),
                notes = listOf(Note("n1", local.key, "chapter-1", 0, "正文", "笔记")),
                bookSettings = mapOf(local.key to ReaderSettings(fontSize = 24f)),
                personalGlossaries = mapOf(local.key to mapOf("word" to "译文"))
            )
            val backup = manifest(state, assets = files.mapValues { LibraryBackupArchive.digest(it.value) }, documents = listOf("volume"))
            val output = ByteArrayOutputStream()
            LibraryBackupArchive.write(output, backup, files)
            val stage = File(root, "stage").apply { mkdirs() }
            val read = LibraryBackupArchive.extract(ByteArrayInputStream(output.toByteArray()), stage)
            assertEquals(state, read.library)
            assertFalse(File(stage, "documents/volume.epub").exists())
            assertArrayEquals(image.readBytes(), File(stage, "documents/volume-images/$imageHash").readBytes())

            val mapped = remapBackupLibrary(read.library, mapOf("volume" to "new-volume")) { "/new/$it/cover" }
            assertEquals(listOf("local/new-volume"), mapped.books.first().volumeOrder)
            assertEquals("wenku/series", mapped.books.last().parentWenkuKey)
            assertEquals("/new/new-volume/cover", mapped.books.last().book.cover)
            assertTrue("local/new-volume" in mapped.positions)
            assertFalse("local/volume" in mapped.readingHistory)
            assertEquals(ReadingHistoryEntry("local/new-volume", "卷一", "chapter-1", "第一章", 123), mapped.readingHistory["local/new-volume"])
            assertEquals("local/new-volume", mapped.notes.single().key)
            assertEquals(24f, mapped.bookSettings["local/new-volume"]!!.fontSize, 0f)
            assertEquals("译文", mapped.personalGlossaries["local/new-volume"]!!["word"])
        } finally { root.deleteRecursively() }
    }

    @Test fun mergeKeepsCurrentChoicesAndTasksButTakesNewerProgress() {
        val ref = BookRef("syosetu", "n1")
        val action = PendingAction("p", "account", "PUT", "safe/path")
        val download = DownloadEntry("d", "current", "current.epub", "https://example.test/file")
        val current = LibraryState(books = listOf(SavedBook(BookCard(ref, "本机标题"), folder = "本机")),
            positions = mapOf(ref.key to Position("old", updatedAt = 10)), notes = listOf(Note("same", ref.key, "old", 0, "", "本机笔记")),
            bookSettings = mapOf(ref.key to ReaderSettings(fontSize = 22f)), theme = "dark", pending = listOf(action), downloads = listOf(download))
        val imported = LibraryState(books = listOf(SavedBook(BookCard(ref, "备份标题"), folder = "备份")),
            positions = mapOf(ref.key to Position("new", updatedAt = 20)), notes = listOf(Note("same", ref.key, "new", 0, "", "备份笔记"), Note("new", ref.key, "new", 0, "", "新增笔记")),
            bookSettings = mapOf(ref.key to ReaderSettings(fontSize = 30f)), theme = "light")
        val merged = mergeLibraryBackup(current, imported)
        assertEquals("本机标题", merged.books.single().book.title)
        assertEquals("本机", merged.books.single().folder)
        assertEquals("new", merged.positions[ref.key]!!.chapterId)
        assertEquals(listOf("本机笔记", "新增笔记"), merged.notes.map { it.text })
        assertEquals(22f, merged.bookSettings[ref.key]!!.fontSize, 0f)
        assertEquals("dark", merged.theme)
        assertEquals(listOf(action), merged.pending)
        assertEquals(listOf(download), merged.downloads)
        assertEquals(merged, mergeLibraryBackup(merged, imported))
        assertEquals("light", mergeLibraryBackup(LibraryState(), imported).theme)
    }

    @Test fun exportRemovesAccountActionsAndDownloadRuntime() {
        val state = LibraryState(pending = listOf(PendingAction("id", "account", "POST", "path", "private queued content")),
            downloads = listOf(DownloadEntry("d", "download", "file", "url", workId = "work")))
        val text = appJson.encodeToString(state.forBackup())
        assertFalse(text.contains("private queued content"))
        assertTrue(state.forBackup().pending.isEmpty())
        assertTrue(state.forBackup().downloads.isEmpty())
    }

    @Test fun backupExcludesInstalledSyncIdentityLogicalClockAndDocuments() {
        val data = LibraryState(notes = listOf(Note("sync-note", "syosetu/book", "chapter", 0, "摘录", "正文")),
            readingHistory = mapOf("syosetu/book" to ReadingHistoryEntry("syosetu/book", "小说", "chapter", "章节", 100)))
        val replica = SyncReplica(deviceId = "device-to-omit").track(emptyMap(), WebDavProjection.library(data)).bind("dataset-to-omit")
        assertTrue(replica.clock > 0)
        assertTrue(replica.documents.isNotEmpty())
        val exported = data.copy(syncReplica = replica).forBackup()
        assertEquals("", exported.syncReplica.deviceId)
        assertEquals(0L, exported.syncReplica.clock)
        assertTrue(exported.syncReplica.documents.isEmpty())
        assertEquals(data.notes, exported.notes)
        assertEquals(data.readingHistory, exported.readingHistory)
        val encoded = appJson.encodeToString(exported)
        assertFalse(encoded.contains("device-to-omit"))
        assertFalse(encoded.contains("dataset-to-omit"))
    }

    @Test fun missingIllustrationAndDanglingVolumeIndexAreRejected() {
        val root = temp()
        try {
            val document = LocalDocument("book", "book", "epub", listOf(LocalChapter("c", "c", listOf("novelia-image:${"a".repeat(64)}"))))
            val source = File(root, "book.json").apply { writeText(appJson.encodeToString(document)) }
            val backup = manifest(assets = mapOf("documents/book.json" to LibraryBackupArchive.digest(source)), documents = listOf("book"))
            val bytes = archive(mapOf("manifest.json" to appJson.encodeToString(backup).toByteArray(), "documents/book.json" to source.readBytes()))
            fails { LibraryBackupArchive.extract(ByteArrayInputStream(bytes), File(root, "images").apply { mkdirs() }) }
            val invalid = manifest(LibraryState(books = listOf(SavedBook(BookCard(BookRef("wenku", "w"), "series"), volumeOrder = listOf("local/missing")))))
            fails { LibraryBackupArchive.extract(ByteArrayInputStream(archive(mapOf("manifest.json" to appJson.encodeToString(invalid).toByteArray()))), File(root, "index").apply { mkdirs() }) }
        } finally { root.deleteRecursively() }
    }

    @Test fun recoveryDistinguishesFirstRunAndKeepsLastGoodReadOnlyAfterCorruption() {
        var attemptedRead = false
        val first = loadLibraryState(false, { attemptedRead = true; "bad" }, { "bad" })
        assertFalse(attemptedRead); assertNull(first.issue)
        val valid = LibraryState(notes = listOf(Note("n", "local/book", "c", 0, "keep", "keep")))
        val protected = loadLibraryState(true, { "{ corrupt" }, { appJson.encodeToString(valid) })
        assertEquals(valid, protected.state)
        assertTrue(protected.issue!!.hasLastGood)
        val unrecoverable = loadLibraryState(true, { "{ corrupt" }, { "also corrupt" })
        assertNotNull(unrecoverable.issue); assertFalse(unrecoverable.issue!!.hasLastGood)
    }

    @Test fun missingMainFileCanRecoverLastGoodAndHealthyMainDoesNotUseFallback() {
        val good = LibraryState(savedSearches = listOf("recover me"))
        val missing = loadLibraryState(true, { throw java.io.FileNotFoundException() }, { appJson.encodeToString(good) })
        assertEquals(good, missing.state)
        assertTrue(missing.issue!!.hasLastGood)
        var readFallback = false
        val healthy = loadLibraryState(true, { appJson.encodeToString(good) }, { readFallback = true; "bad backup" })
        assertEquals(good, healthy.state)
        assertNull(healthy.issue)
        assertFalse(readFallback)
    }
}
