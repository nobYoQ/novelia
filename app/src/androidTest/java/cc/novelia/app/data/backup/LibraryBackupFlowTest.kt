package cc.novelia.app.data.backup

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.catalog.KeywordStore
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
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.data.storage.LocalStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 使用真实 Android AtomicFile 验证，测试书库与应用的用户书库隔离。 */
@RunWith(AndroidJUnit4::class)
class LibraryBackupFlowTest {
    private class IsolatedContext(base: Context, private val directory: File) : ContextWrapper(base) {
        override fun getFilesDir(): File = File(directory, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(directory, "cache").apply { mkdirs() }
    }

    private suspend fun fixtures(block: suspend (Context, Context) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "library-backup-test-${UUID.randomUUID()}").apply { mkdirs() }
        try { block(IsolatedContext(base, File(root, "source")), IsolatedContext(base, File(root, "target"))) }
        finally {
            // 目录写入合并器可能仍持有已刷盘的请求，等待其处理完毕。
            delay(350)
            root.deleteRecursively()
        }
    }

    @Test fun laterBackupAddsMissingOriginalsWithoutOverwritingExistingFilesAndRollsBackOnFailure() = runBlocking {
        withContext(Dispatchers.IO) { fixtures { sourceContext, targetContext ->
            val source = LocalStore(sourceContext); val sourceTags = KeywordStore(sourceContext)
            val ref = BookRef("local", "original-upgrade")
            source.saveDocument(LocalDocument(ref.id, "原件补全", "txt", listOf(LocalChapter("c", "正文", listOf("阅读正文"))), sourceHash = "same-source"))
            source.saveBook(BookCard(ref, "原件补全"))
            source.documentSource(ref.id, "txt").writeText("原始文本内容", Charsets.UTF_8)
            val exporter = LibraryBackupService(source, sourceTags)
            val parsedOnly = ByteArrayOutputStream().also { exporter.export(it, false) }.toByteArray()
            val withOriginal = ByteArrayOutputStream().also { exporter.export(it, true) }.toByteArray()
            val target = LocalStore(targetContext)
            val service = LibraryBackupService(target, KeywordStore(targetContext))
            assertNull(service.restore(service.prepare(ByteArrayInputStream(parsedOnly)).stagingId))
            val retainedRef = target.state.value.books.single().book.ref
            val original = target.documentSource(retainedRef.id, "txt")
            assertFalse(original.exists())
            val upgrade = service.prepare(ByteArrayInputStream(withOriginal))
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val obstruction = File(targetContext.filesDir, "library.json.new").apply { mkdirs() }
                File(obstruction, "blocked").writeText("force write failure")
                try {
                    try { service.restore(upgrade.stagingId); fail("commit must fail") }
                    catch (_: java.io.IOException) { }
                    assertFalse("未提交恢复必须移除补拷的原件", original.exists())
                    assertEquals(retainedRef, target.state.value.books.single().book.ref)
                } finally { obstruction.deleteRecursively() }
            }
            assertNull(service.restore(upgrade.stagingId))
            assertEquals(retainedRef, target.state.value.books.single().book.ref)
            assertEquals("原始文本内容", original.readText(Charsets.UTF_8))
            original.writeText("本机已有原件必须保留", Charsets.UTF_8)
            assertNull(service.restore(service.prepare(ByteArrayInputStream(withOriginal)).stagingId))
            assertEquals("本机已有原件必须保留", original.readText(Charsets.UTF_8))
        } }
    }

    @Test fun corruptExistingIllustrationRestoresACompleteCopyAndRepeatedRecoveryReusesIt() = runBlocking {
        withContext(Dispatchers.IO) { fixtures { sourceContext, targetContext ->
            val source = LocalStore(sourceContext); val sourceTags = KeywordStore(sourceContext)
            val ref = BookRef("local", "image-repair")
            val hash = "b".repeat(64); val picture = byteArrayOf(5, 8, 13, 21)
            source.saveDocument(LocalDocument(ref.id, "图片修复", "epub", listOf(LocalChapter("c", "正文", listOf("正文", "novelia-image:$hash"))),
                images = mapOf(hash to java.util.Base64.getEncoder().encodeToString(picture)), coverImage = hash, sourceHash = "image-source"))
            source.saveBook(BookCard(ref, "图片修复"))
            val bytes = ByteArrayOutputStream().also { LibraryBackupService(source, sourceTags).export(it, false) }.toByteArray()
            val target = LocalStore(targetContext)
            val service = LibraryBackupService(target, KeywordStore(targetContext))
            assertNull(service.restore(service.prepare(ByteArrayInputStream(bytes)).stagingId))
            val damaged = target.state.value.books.single().book.ref
            target.documentImage(damaged.id, hash).writeBytes(byteArrayOf(0))
            assertNull(service.restore(service.prepare(ByteArrayInputStream(bytes)).stagingId))
            val repaired = target.state.value.books.single { it.book.ref != damaged }.book.ref
            assertArrayEquals(picture, target.documentImage(repaired.id, hash).readBytes())
            assertArrayEquals("旧副本保持原状以便人工恢复", byteArrayOf(0), target.documentImage(damaged.id, hash).readBytes())
            assertNull(service.restore(service.prepare(ByteArrayInputStream(bytes)).stagingId))
            assertEquals("重复恢复应复用已完整恢复的副本", 2, target.state.value.books.size)
        } }
    }

    @Test fun restoreRepairsLongTextDeletedAfterTheSameProcessLoadedIt() = runBlocking {
        withContext(Dispatchers.IO) { fixtures { sourceContext, targetContext ->
            val source = LocalStore(sourceContext); val sourceTags = KeywordStore(sourceContext)
            source.update { it.copy(drafts = mapOf("article:new" to "草稿原文")) }
            val bytes = ByteArrayOutputStream().also { LibraryBackupService(source, sourceTags).export(it, false) }.toByteArray()
            val target = LocalStore(targetContext)
            val service = LibraryBackupService(target, KeywordStore(targetContext))
            assertNull(service.restore(service.prepare(ByteArrayInputStream(bytes)).stagingId))
            File(targetContext.filesDir, "library-text").listFiles().orEmpty().forEach { assertTrue(it.delete()) }
            assertNull(service.restore(service.prepare(ByteArrayInputStream(bytes)).stagingId))
            val reopened = LocalStore(targetContext)
            assertNull(reopened.recoveryIssue.value)
            assertEquals("草稿原文", reopened.state.value.drafts["article:new"])
        } }
    }

    @Test fun backupRestoresReadableVolumesImagesOriginalsProgressNotesAndTranslations() = runBlocking {
        withContext(Dispatchers.IO) { fixtures { sourceContext, targetContext ->
            val source = LocalStore(sourceContext); val sourceTags = KeywordStore(sourceContext)
            val parent = BookRef("wenku", "backup-series"); val first = BookRef("local", "backup-first"); val second = BookRef("local", "backup-second")
            val image = byteArrayOf(1, 5, 9); val imageHash = "a".repeat(64)
            source.saveDocument(LocalDocument(first.id, "第一卷", "epub", listOf(LocalChapter("c1", "第一章", listOf("正文", "novelia-image:$imageHash"))),
                images = mapOf(imageHash to java.util.Base64.getEncoder().encodeToString(image)), coverImage = imageHash, sourceHash = "source-one"))
            source.saveDocument(LocalDocument(second.id, "第二卷", "txt", listOf(LocalChapter("c2", "第二章", listOf("续篇正文"))), sourceHash = "source-two"))
            source.documentSource(first.id, "epub").writeBytes(byteArrayOf(10, 20, 30))
            // 第二本书刻意不提供原始文件，恢复仍须保留已解析的正文。
            source.update { it.copy(books = listOf(
                SavedBook(BookCard(parent, "系列"), folder = "收藏夹", volumeOrder = listOf(second.key, first.key)),
                SavedBook(BookCard(first, "第一卷"), folder = "收藏夹", parentWenkuKey = parent.key),
                SavedBook(BookCard(second, "第二卷"), folder = "收藏夹", parentWenkuKey = parent.key)), folders = listOf("收藏夹"),
                positions = mapOf(first.key to Position("c1", index = 1, offset = 7, textOffset = 3, updatedAt = 123)),
                notes = listOf(Note("backup-note", first.key, "c1", 0, "正文", "读书笔记")),
                bookSettings = mapOf(first.key to ReaderSettings(fontSize = 25f)),
                personalGlossaries = mapOf(first.key to mapOf("原文" to "译文")),
                pending = listOf(PendingAction("not-portable", "account", "PUT", "pending/path")),
                downloads = listOf(DownloadEntry("running", "task", "task.epub", "https://example.test/task", status = "下载中", workId = "work-id"))) }
            sourceTags.setTranslation("ヤンデレ", "用户译名")
            val bytes = ByteArrayOutputStream().also { LibraryBackupService(source, sourceTags).export(it, includeOriginals = true) }.toByteArray()

            val target = LocalStore(targetContext); val targetTags = KeywordStore(targetContext)
            val service = LibraryBackupService(target, targetTags)
            val preview = service.prepare(ByteArrayInputStream(bytes))
            assertEquals(3, preview.books); assertEquals(2, preview.documents); assertEquals(1, preview.notes)
            // 重新创建服务，模拟界面重建后仅凭暂存 ID 继续恢复。
            assertEquals(preview, LibraryBackupService(target, targetTags).preview(preview.stagingId))
            assertNull(service.restore(preview.stagingId))
            target.flush()
            val persisted = LocalStore(targetContext)
            val restored = persisted.state.value
            val restoredFirst = restored.books.single { it.book.title == "第一卷" }.book.ref
            val restoredSecond = restored.books.single { it.book.title == "第二卷" }.book.ref
            assertNotEquals(first.id, restoredFirst.id)
            assertEquals(listOf(restoredSecond.key, restoredFirst.key), restored.books.single { it.book.ref == parent }.volumeOrder)
            assertEquals(parent.key, restored.books.single { it.book.ref == restoredFirst }.parentWenkuKey)
            assertEquals(Position("c1", index = 1, offset = 7, textOffset = 3, updatedAt = 123), restored.positions[restoredFirst.key])
            assertEquals(restoredFirst.key, restored.notes.single().key)
            assertEquals("读书笔记", restored.notes.single().text)
            assertEquals(25f, restored.bookSettings.getValue(restoredFirst.key).fontSize, 0f)
            assertEquals("译文", restored.personalGlossaries.getValue(restoredFirst.key)["原文"])
            assertEquals(listOf("续篇正文"), persisted.document(restoredSecond.id).chapters.single().paragraphs)
            assertArrayEquals(image, persisted.documentImage(restoredFirst.id, imageHash).readBytes())
            assertArrayEquals(byteArrayOf(10, 20, 30), persisted.documentSource(restoredFirst.id, "epub").readBytes())
            assertFalse(persisted.documentSource(restoredSecond.id, "txt").exists())
            assertTrue(restored.pending.isEmpty()); assertTrue(restored.downloads.isEmpty())
            assertEquals("用户译名", KeywordStore(targetContext).state.value.entries.single { it.original == "ヤンデレ" }.translation)

            targetTags.setTranslation("ヤンデレ", "本机后续译名")
            target.savePosition(restoredFirst, Position("c1", index = 1, textOffset = 8, updatedAt = 999))
            val repeated = service.prepare(ByteArrayInputStream(bytes))
            assertNull(service.restore(repeated.stagingId))
            assertEquals("重复恢复不得复制相同本地正文", 3, target.state.value.books.size)
            assertEquals(8, target.state.value.positions.getValue(restoredFirst.key).textOffset)
            assertEquals("本机后续译名", targetTags.state.value.entries.single { it.original == "ヤンデレ" }.translation)
        } }
    }

    @Test fun damagedLibraryIsReadOnlyUntilExplicitLastGoodRecovery() = runBlocking {
        withContext(Dispatchers.IO) { fixtures { context, _ ->
            val original = LocalStore(context)
            original.update { it.copy(savedSearches = listOf("valuable search"), notes = listOf(Note("n", "syosetu/n1", "c", 1, "保留", "笔记"))) }
            original.flush()
            val file = File(context.filesDir, "library.json")
            val corrupt = "{damaged data that must survive"
            file.writeText(corrupt, Charsets.UTF_8)
            val protected = LocalStore(context)
            assertTrue(protected.recoveryIssue.value!!.hasLastGood)
            assertEquals(listOf("valuable search"), protected.state.value.savedSearches)
            protected.update { LibraryState() }; protected.flush()
            assertEquals(corrupt, file.readText(Charsets.UTF_8))
            protected.recoverLastGood()
            assertNull(protected.recoveryIssue.value)
            val reopened = LocalStore(context)
            assertEquals("笔记", reopened.state.value.notes.single().text)
            assertTrue(context.filesDir.listFiles().orEmpty().any { it.name.startsWith("library-damaged-") && it.readText(Charsets.UTF_8) == corrupt })
            protected.update { it.copy(savedSearches = listOf("writes work again")) }; protected.flush()
            assertEquals(listOf("writes work again"), LocalStore(context).state.value.savedSearches)
        } }
    }

    @Test fun failedArchiveValidationCannotChangeExistingAtomicLibrary() = runBlocking {
        withContext(Dispatchers.IO) { fixtures { context, _ ->
            val store = LocalStore(context); val tags = KeywordStore(context)
            store.update { it.copy(savedSearches = listOf("keep me"), books = listOf(SavedBook(BookCard(BookRef("syosetu", "n1"), "已有书籍")))) }
            store.flush()
            val before = File(context.filesDir, "library.json").readBytes()
            val malicious = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("../library.json")); zip.write("overwrite".toByteArray()); zip.closeEntry()
            } }.toByteArray()
            try { LibraryBackupService(store, tags).prepare(ByteArrayInputStream(malicious)); fail("must reject traversal") }
            catch (_: IllegalArgumentException) { /* 验证应用层的归档路径检查。 */ }
            catch (_: java.util.zip.ZipException) { /* 较新 Android 会先在 nextEntry 中拒绝路径穿越。 */ }
            assertArrayEquals(before, File(context.filesDir, "library.json").readBytes())
            assertEquals(listOf("keep me"), LocalStore(context).state.value.savedSearches)
            assertEquals("已有书籍", store.state.value.books.single().book.title)
        } }
    }

    @Test fun failedAtomicCommitRollsBackNewDocumentFilesAndCanRetryTheSameStage() = runBlocking {
        org.junit.Assume.assumeTrue("此故障注入针对使用 .new 暂存文件的 Android AtomicFile", android.os.Build.VERSION.SDK_INT >= 30)
        withContext(Dispatchers.IO) { fixtures { sourceContext, targetContext ->
            val source = LocalStore(sourceContext); val sourceTags = KeywordStore(sourceContext)
            source.saveDocument(LocalDocument("new-book", "待恢复小说", "txt", listOf(LocalChapter("c", "正文", listOf("新资料"))), sourceHash = "new-source"))
            source.saveBook(BookCard(BookRef("local", "new-book"), "待恢复小说"))
            val bytes = ByteArrayOutputStream().also { LibraryBackupService(source, sourceTags).export(it, false) }.toByteArray()
            val target = LocalStore(targetContext); val targetTags = KeywordStore(targetContext)
            target.update { it.copy(savedSearches = listOf("existing data")) }; target.flush()
            val beforeState = target.state.value
            val beforeBytes = File(targetContext.filesDir, "library.json").readBytes()
            val service = LibraryBackupService(target, targetTags)
            val preview = service.prepare(ByteArrayInputStream(bytes))
            // Android AtomicFile 写入 .new 文件；用非空目录制造真实的 IO 失败。
            val obstruction = File(targetContext.filesDir, "library.json.new").apply { mkdirs() }
            File(obstruction, "blocked").writeText("force write failure")
            try { service.restore(preview.stagingId); fail("atomic commit must fail") }
            catch (_: java.io.IOException) { }
            assertEquals(beforeState, target.state.value)
            assertArrayEquals(beforeBytes, File(targetContext.filesDir, "library.json").readBytes())
            assertTrue("失败恢复不得遗留本次新正文", target.documentsDir.listFiles().orEmpty().none { it.extension == "json" && it.name != "source-index.json" })
            obstruction.deleteRecursively()
            assertNull(service.restore(preview.stagingId))
            val restoredRef = target.state.value.books.single().book.ref
            assertEquals(listOf("新资料"), target.document(restoredRef.id).chapters.single().paragraphs)
            assertEquals(listOf("existing data"), target.state.value.savedSearches)
        } }
    }
}
