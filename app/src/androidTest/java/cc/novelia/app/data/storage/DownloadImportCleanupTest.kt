package cc.novelia.app.data.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.DownloadEntry
import cc.novelia.app.files.importDownloadedDocument
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadImportCleanupTest {
    @Test fun completedImportCleansDownloadOnlyWhenEnabledAndFailedImportKeepsSource() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        app.initialization.await()
        val before = app.store.state.value
        val id = UUID.randomUUID().toString()
        val entry = DownloadEntry(id, "测试下载", "$id-zh.txt", "https://example.invalid/file.txt", status = "已完成")
        val source = File(app.store.downloadsDir, entry.fileName).apply { writeText("第一章\n清理测试 $id", Charsets.UTF_8) }
        val broken = entry.copy(id = "$id-broken", fileName = "$id-broken.epub")
        val brokenSource = File(app.store.downloadsDir, broken.fileName).apply { writeText("无效 EPUB", Charsets.UTF_8) }
        var importedId: String? = null
        try {
            app.store.update { it.copy(downloads = listOf(entry, broken), deleteDownloadAfterImport = false) }
            val first = importDownloadedDocument(app, entry)
            importedId = first.ref.id
            assertTrue(source.isFile)
            assertTrue(app.store.state.value.downloads.any { it.id == entry.id })
            app.store.update { it.copy(deleteDownloadAfterImport = true) }
            val repeated = importDownloadedDocument(app, entry)
            assertEquals(first.ref, repeated.ref)
            assertFalse(repeated.imported)
            assertFalse(source.exists())
            assertFalse(app.store.state.value.downloads.any { it.id == entry.id })
            assertTrue(app.store.documentSource(first.ref.id, "txt").isFile)
            try { importDownloadedDocument(app, broken); fail("Invalid EPUB must fail") } catch(expected: Exception) { /* 源文件仍需保留。 */ }
            assertTrue(brokenSource.isFile)
            assertTrue(app.store.state.value.downloads.any { it.id == broken.id })
        } finally {
            importedId?.let { app.store.removeDocument(it) }
            app.store.update { before }
            app.store.flush()
            source.delete(); brokenSource.delete()
        }
    }
}
