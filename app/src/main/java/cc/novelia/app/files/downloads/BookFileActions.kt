package cc.novelia.app.files.downloads

import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.library.bookDeletionPlan
import cc.novelia.app.data.library.downloadedVolume
import cc.novelia.app.data.library.originBook
import cc.novelia.app.data.library.originVolumeId
import cc.novelia.app.data.library.withDeletedBookRecords
import cc.novelia.app.data.library.withDownloadLink
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.DownloadEntry
import cc.novelia.app.data.storage.LocalStore
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import cc.novelia.app.files.DocumentTools
import cc.novelia.app.files.DownloadWorker
import cc.novelia.app.files.importing.documentImportLock
import cc.novelia.app.files.importing.importDownloadedDocument

/** 下载导入和关联删除共用此锁，防止删除完成后并发导入重新挂载书籍。 */
internal val downloadedBookLock = Mutex()

internal fun LocalStore.downloadFile(entry: DownloadEntry): File {
    val file = File(downloadsDir, entry.fileName)
    require(entry.fileName == file.name && file.canonicalFile.parentFile == downloadsDir.canonicalFile) { "下载文件路径无效" }
    return file
}

suspend fun downloadedVolumeForReading(app: NoveliaApplication, source: BookRef, volumeId: String): BookRef {
    val local = withContext(Dispatchers.IO) {
        app.store.state.value.downloadedVolume(source, volumeId)?.takeIf { ref ->
            runCatching { app.store.documentIndex(ref.id).chapters.isNotEmpty() }.getOrDefault(false)
        }
    }
    if(local != null) return local
    val entry = app.store.state.value.downloads.lastOrNull {
        it.status == "已完成" && it.originBook() == source && it.originVolumeId() == volumeId
    } ?: error("此分卷没有可阅读的下载文件，请重新下载")
    return importDownloadedDocument(app, entry).ref
}

/**
 * 旧安装没有持久关联时，通过完整源文件哈希补回副本身份。
 * 不按相同书名删除文件，也不将失败/未完成下载当作已导入副本。
 */
private suspend fun recoverDownloadLinks(store: LocalStore) {
    val work = kotlin.coroutines.coroutineContext
    val initial = store.state.value
    // 同时补齐其他来源的旧关联，保护哈希去重后被多个作品共用的副本。
    initial.downloads.filter { entry ->
        entry.status == "已完成" && initial.downloadLinks.none { it.downloadId == entry.id }
    }.forEach { entry ->
        work.ensureActive()
        val ref = try {
            val file = store.downloadFile(entry)
            if(!file.isFile) return@forEach
            DocumentTools.requireImportSize(file.length())
            val hash = file.inputStream().use { DocumentTools.digest(it) { work.ensureActive() } }
            store.findDocumentByHash(hash) { work.ensureActive() }
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(_: Exception) { null }
        if(ref != null) store.update { it.withDownloadLink(entry, ref) }
    }
}

/** 删除所选书籍/分卷的副本和对应下载；整本文库还包含全部挂载分卷。 */
suspend fun deleteBookFiles(app: NoveliaApplication, refs: Set<BookRef> = emptySet(),
    downloadIds: Set<String> = emptySet(), eraseReadingData: Boolean = true) = withContext(Dispatchers.IO) {
    downloadedBookLock.withLock {
        documentImportLock.withLock {
            val store = app.store
            check(store.recoveryIssue.value == null) { "本地资料已保护，请先前往资料备份与恢复" }
            recoverDownloadLinks(store)
            val plan = store.state.value.bookDeletionPlan(refs, downloadIds, includeSourceBooks = eraseReadingData)
            // 取消正在下载的任务并持久化删除，旧 Worker 不能再次发布成品。
            withContext(NonCancellable) {
                plan.downloadIds.forEach { DownloadWorker.remove(app, it) }
                store.update { it.withDeletedBookRecords(plan.books, eraseReadingData, plan.linkIds) }
                store.flush()
                plan.books.filter { it.isLocal }.forEach { store.removeDocument(it.id) }
                store.flush()
            }
        }
    }
}
