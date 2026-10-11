package cc.novelia.app.data.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.library.downloadedVolume
import cc.novelia.app.data.model.*
import cc.novelia.app.files.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import cc.novelia.app.files.downloads.deleteBookFiles
import cc.novelia.app.files.downloads.downloadedVolumeForReading
import cc.novelia.app.files.importing.importDownloadedDocument
import cc.novelia.app.files.importing.importLocalDocument

@RunWith(AndroidJUnit4::class)
class BookFileActionsTest {
    private fun app() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication

    @Test fun bothCleanupPreferencesRemainIndependentAndCompleteDeletionAlwaysCleansAssociatedFiles() = runBlocking {
        val app = app(); app.initialization.await()
        val before = app.store.state.value
        val documents = mutableListOf<BookRef>(); val files = mutableListOf<File>()
        try {
            for(deleteDownload in listOf(false, true)) for(deleteOnRemoval in listOf(false, true)) {
                val id = UUID.randomUUID().toString()
                val parent = BookRef("wenku", "settings-$id")
                val card = BookCard(parent, "清理开关测试")
                val entry = DownloadEntry(id, "第1卷.txt", "$id-zh.txt", "https://example.invalid/file", status = "已完成", sourceBook = parent, sourceCard = card)
                val source = File(app.store.downloadsDir, entry.fileName).apply { writeText("第一章\n设置组合 $id", Charsets.UTF_8) }; files += source
                app.store.update { it.copy(deleteDownloadAfterImport = deleteDownload, deleteLocalCopyOnShelfRemoval = deleteOnRemoval, downloads = it.downloads + entry) }
                val ref = importDownloadedDocument(app, entry).ref; documents += ref
                assertEquals(!deleteDownload, source.exists())
                assertTrue(app.store.documentSource(ref.id, "txt").isFile)
                assertEquals(ref, downloadedVolumeForReading(app, parent, entry.title))
                app.store.flush()
                assertEquals(ref, LocalStore(app).state.value.downloadedVolume(parent, entry.title))

                app.store.removeShelfBook(ref)
                assertEquals(!deleteOnRemoval, app.store.documentSource(ref.id, "txt").exists())
                // 移出书架的清理开关只控制导入副本，不额外清理下载源。
                assertEquals(!deleteDownload, source.exists())
                deleteBookFiles(app, setOf(parent))
                assertFalse(source.exists())
                assertFalse(app.store.documentSource(ref.id, "txt").exists())
                assertFalse(app.store.state.value.books.any { it.book.ref == parent || it.book.ref == ref })
                assertFalse(app.store.state.value.downloads.any { it.id == id })
                assertEquals(deleteDownload, app.store.state.value.deleteDownloadAfterImport)
                assertEquals(deleteOnRemoval, app.store.state.value.deleteLocalCopyOnShelfRemoval)
            }
        } finally {
            documents.forEach { app.store.removeDocument(it.id) }
            files.forEach { it.delete() }
            app.store.update { before }; app.store.flush()
        }
    }

    @Test fun deletingOneVolumeCleansItsDownloadAndDeletingSeriesCleansAllRemainingTasks() = runBlocking {
        val app = app(); app.initialization.await(); val before = app.store.state.value
        val parent = BookRef("wenku", "delete-${UUID.randomUUID()}")
        val card = BookCard(parent, "整书删除测试")
        val entries = (1..3).map { number ->
            val id = UUID.randomUUID().toString()
            DownloadEntry(id, "第${number}卷.txt", "$id-zh.txt", "https://example.invalid/file", status = if(number == 3) "已暂停" else "已完成", sourceBook = parent, sourceCard = card)
        }
        val files = entries.map { File(app.store.downloadsDir, it.fileName).apply { writeText("第一章\n分卷 ${it.id}", Charsets.UTF_8) } }
        val refs = mutableListOf<BookRef>()
        try {
            app.store.update { it.copy(deleteDownloadAfterImport = false, deleteLocalCopyOnShelfRemoval = false, downloads = it.downloads + entries) }
            entries.take(2).forEach { refs += importDownloadedDocument(app, it).ref }
            app.store.savePosition(refs.first(), Position("c"))
            deleteBookFiles(app, setOf(refs.first()), eraseReadingData = false)
            assertFalse(files.first().exists()); assertTrue(files[1].exists()); assertTrue(files[2].exists())
            assertTrue(app.store.state.value.books.any { it.book.ref == parent })
            assertTrue(app.store.state.value.positions.containsKey(refs.first().key))
            deleteBookFiles(app, setOf(parent))
            assertTrue(files.none { it.exists() })
            assertTrue(refs.none { app.store.documentSource(it.id, "txt").exists() })
            assertTrue(app.store.state.value.downloads.none { it.sourceBook == parent })
        } finally {
            refs.forEach { app.store.removeDocument(it.id) }; files.forEach { it.delete() }
            app.store.update { before }; app.store.flush()
        }
    }

    @Test fun legacyImportedDownloadIsLinkedByContentAndDeletedWithItsSourceBook() = runBlocking {
        val app = app(); app.initialization.await(); val before = app.store.state.value
        val id = UUID.randomUUID().toString(); val sourceBook = BookRef("syosetu", "legacy-$id")
        val entry = DownloadEntry(id, "旧版下载", "$id.txt", "https://n.novelia.cc/api/novel/${sourceBook.key}/file", status = "已完成")
        val source = File(app.store.downloadsDir, entry.fileName).apply { writeText("第一章\n旧版本关联 $id", Charsets.UTF_8) }
        var local: BookRef? = null
        try {
            local = importLocalDocument(app.store, source).ref
            app.store.saveBook(BookCard(sourceBook, entry.title))
            app.store.update { it.copy(downloads = it.downloads + entry) }
            deleteBookFiles(app, setOf(sourceBook))
            assertFalse(source.exists()); assertFalse(app.store.documentSource(local.id, "txt").exists())
            assertTrue(app.store.state.value.books.none { it.book.ref == sourceBook || it.book.ref == local })
        } finally {
            local?.let { app.store.removeDocument(it.id) }; source.delete()
            app.store.update { before }; app.store.flush()
        }
    }
}
