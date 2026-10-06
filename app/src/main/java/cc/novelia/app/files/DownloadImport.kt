package cc.novelia.app.files

import android.net.Uri
import android.provider.OpenableColumns
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.library.withDownloadedVolume
import cc.novelia.app.data.library.withWenkuSiteOrder
import cc.novelia.app.data.library.siteVolumeIds
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.DownloadEntry
import cc.novelia.app.data.model.WenkuDetail
import cc.novelia.app.data.storage.LocalStore
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.storage.hashName
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** imported 仅在本次创建新文档时为 true；命中源文件哈希会返回现有引用。 */
data class DocumentImportResult(val ref: BookRef, val imported: Boolean)
private val importLock = Mutex()

private fun copyImport(input: InputStream, target: File, checkCancelled: () -> Unit) {
    target.outputStream().use { output ->
        val buffer = ByteArray(65536)
        var total = 0L
        while (true) {
            checkCancelled()
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            DocumentTools.requireImportSize(total)
            output.write(buffer, 0, count)
        }
    }
}

/**
 * 将文档提供器的 URI 流暂存到磁盘，再进入统一导入流程。
 * 提供器声明的大小可能缺失或不可信，因此复制时仍逐块检查上限和取消状态；
 * finally 清理本次输入暂存文件，不要求提供器返回可以直接访问的本地文件路径。
 */
suspend fun importDocumentUri(store: LocalStore, uri: Uri, onProgress: (String) -> Unit = {}): DocumentImportResult = withContext(Dispatchers.IO) {
    onProgress("正在读取文件")
    val resolver = store.context.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
        if (it.moveToFirst()) {
            val sizeColumn = it.getColumnIndex(OpenableColumns.SIZE)
            if (sizeColumn >= 0 && !it.isNull(sizeColumn)) it.getLong(sizeColumn).takeIf { size -> size >= 0 }?.let(DocumentTools::requireImportSize)
            val nameColumn = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameColumn >= 0) it.getString(nameColumn) else null
        } else null
    } ?: "导入文档.txt"
    val staged = File.createTempFile("document-input-", ".tmp", store.context.cacheDir)
    try {
        val workContext = coroutineContext
        resolver.openInputStream(uri)?.use { copyImport(it, staged) { workContext.ensureActive() } } ?: error("无法读取文件")
        importLocalDocument(store, staged, name, onProgress = onProgress)
    } finally { staged.delete() }
}

/**
 * 串行完成哈希去重、解析、资源安装和加入书架，防止并发导入同一文件产生两份书籍。
 * 命中源文件哈希时返回原引用且 imported=false，保留已有阅读位置和用户的分卷归属。
 * 新文档使用新 ID；安装失败仅清理这个新文档，图片先流式写入暂存目录以控制内存占用。
 */
suspend fun importLocalDocument(store: LocalStore, file: File, name: String = file.name, title: String? = null,
    downloadMode: String? = null, onProgress: (String) -> Unit = {}): DocumentImportResult = withContext(Dispatchers.IO) {
    importLock.withLock {
        onProgress("正在检查重复文件")
        DocumentTools.requireImportSize(file.length())
        val workContext = coroutineContext
        val hash = file.inputStream().use { DocumentTools.digest(it) { workContext.ensureActive() } }
        store.findDocumentByHash(hash) { workContext.ensureActive() }?.let {
            if (downloadMode != null) store.recordDocumentDownloadMode(it.id, downloadMode)
            if(store.state.value.books.none { saved -> saved.book.ref == it }) {
                val document = store.documentIndex(it.id)
                store.saveBook(BookCard(it, document.name,
                    cover = document.coverImage?.let { image -> store.documentImage(document.id, image).absolutePath },
                    subtitle = "${document.format.uppercase()} · ${document.chapters.size} 章"))
            }
            return@withLock DocumentImportResult(it, false)
        }
        val images = java.nio.file.Files.createTempDirectory(store.context.cacheDir.toPath(), "document-images-").toFile()
        try {
            onProgress("正在解析章节")
            val parsed = DocumentTools.parseFile(name, file, { imageHash, input ->
                copyImport(input, File(images, imageHash)) { workContext.ensureActive() }
            }, hash, downloadMode) { workContext.ensureActive() }
            val document = if (title == null) parsed else parsed.copy(name = title)
            val ref = BookRef("local", document.id)
            try {
                onProgress("正在保存小说")
                file.inputStream().use { copyImport(it, store.documentSource(document.id, document.format)) { workContext.ensureActive() } }
                images.listFiles().orEmpty().forEach { image ->
                    workContext.ensureActive()
                    val destination = store.documentImage(document.id, image.name).apply { parentFile?.mkdirs() }
                    if (!image.renameTo(destination)) image.copyTo(destination, overwrite = true)
                }
                store.saveDocument(document) { workContext.ensureActive() }
                store.saveBook(BookCard(ref, document.name,
                    cover = document.coverImage?.let { store.documentImage(document.id, it).absolutePath },
                    subtitle = "${document.format.uppercase()} · ${document.chapters.size} 章"))
                DocumentImportResult(ref, true)
            } catch (error: Exception) {
                // 本次生成的新 ID 不可能对应已有的阅读副本。
                runCatching { store.removeDocument(document.id) }
                throw error
            }
        } finally { images.deleteRecursively() }
    }
}

/** 开始阅读和批量导入共用来源解析、文件去重和文库挂载。 */
suspend fun importDownloadedDocument(app: NoveliaApplication, entry: DownloadEntry, onProgress: (String) -> Unit = {}): DocumentImportResult {
    require(entry.status == "已完成") { "文件尚未下载完成" }
    val parent = entry.sourceBook?.takeIf { it.isWenku }
    val sourceCard = entry.sourceCard ?: parent?.let { source ->
        app.store.state.value.books.firstOrNull { it.book.ref == source }?.book ?: try {
            val binding = app.session.capture()
            val cached = withContext(Dispatchers.IO) {
                val key = hashName("${binding.cacheAccount}:wenku/${source.id}")
                app.metadataCache.read(key)?.let { runCatching { appJson.decodeFromString<WenkuDetail>(it).card(source) }.getOrNull() }
            }
            app.session.ensureCurrent(binding)
            cached ?: withTimeoutOrNull(1_500) {
                app.api.get<WenkuDetail>("wenku/${source.id}").card(source).also { app.session.ensureCurrent(binding) }
            }
        } catch(e: CancellationException) { throw e }
        catch(_: Exception) { null }
    }
    val result = importDownloadedDocumentResult(app.store, entry, sourceCard, onProgress)
    if(app.store.state.value.deleteDownloadAfterImport) {
        // 必须先持久化可独立阅读的书架副本，才删除下载管理中的源文件。
        app.store.flush()
        DownloadWorker.remove(app, entry.id)
    }
    return result
}

/** 每次导入均补齐父文库并挂载，重复文件复用已有副本和阅读位置。 */
suspend fun importDownloadedDocument(store: LocalStore, entry: DownloadEntry, sourceCard: BookCard? = entry.sourceCard): BookRef =
    importDownloadedDocumentResult(store, entry, sourceCard).ref

private suspend fun importDownloadedDocumentResult(store: LocalStore, entry: DownloadEntry, sourceCard: BookCard?,
    onProgress: (String) -> Unit = {}): DocumentImportResult {
    val result = importLocalDocument(store, File(store.downloadsDir, entry.fileName), entry.fileName, entry.title, entry.contentMode(), onProgress)
    entry.sourceBook?.takeIf { it.isWenku }?.let { source -> store.update { state ->
        val parent = state.books.firstOrNull { it.book.ref == source }?.book
            ?: sourceCard?.takeIf { it.ref == source }
            ?: BookCard(source, "文库小说 ${source.id}", subtitle = "文库小说")
        val mounted = state.withDownloadedVolume(result.ref, parent).let { library ->
            library.copy(books = library.books.map { saved ->
                if(saved.book.ref == result.ref) saved.copy(sourceVolumeId = entry.title) else saved
            })
        }
        val ids = siteVolumeIds(sourceCard?.volumeIds?.takeIf { it.isNotEmpty() } ?: parent.volumeIds)
        if(ids.isEmpty()) mounted else mounted.withWenkuSiteOrder(source.key, ids,
            mounted.books.first { it.book.ref == source }.siteVolumeOrderDescending)
    }
    }
    return result
}
