package cc.novelia.app.files

import android.net.Uri
import android.provider.OpenableColumns
import cc.novelia.app.data.library.withVolumeParent
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.DownloadEntry
import cc.novelia.app.data.storage.LocalStore
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

/** Stage provider streams on disk, keeping compressed books and illustrations out of heap. */
suspend fun importDocumentUri(store: LocalStore, uri: Uri): DocumentImportResult = withContext(Dispatchers.IO) {
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
        importLocalDocument(store, staged, name)
    } finally { staged.delete() }
}

suspend fun importLocalDocument(store: LocalStore, file: File, name: String = file.name, title: String? = null): DocumentImportResult = withContext(Dispatchers.IO) {
    importLock.withLock {
        DocumentTools.requireImportSize(file.length())
        val workContext = coroutineContext
        val hash = file.inputStream().use { DocumentTools.digest(it) { workContext.ensureActive() } }
        store.findDocumentByHash(hash) { workContext.ensureActive() }?.let { return@withLock DocumentImportResult(it, false) }
        val images = java.nio.file.Files.createTempDirectory(store.context.cacheDir.toPath(), "document-images-").toFile()
        try {
            val parsed = DocumentTools.parseFile(name, file, { imageHash, input ->
                copyImport(input, File(images, imageHash)) { workContext.ensureActive() }
            }, hash) { workContext.ensureActive() }
            val document = if (title == null) parsed else parsed.copy(name = title)
            val ref = BookRef("local", document.id)
            try {
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
                // This newly generated ID can never be an existing reading copy.
                runCatching { store.removeDocument(document.id) }
                throw error
            }
        } finally { images.deleteRecursively() }
    }
}

/** Reimporting preserves explicit choices to detach or move an existing volume. */
suspend fun importDownloadedDocument(store: LocalStore, entry: DownloadEntry): BookRef {
    val result = importLocalDocument(store, File(store.downloadsDir, entry.fileName), entry.fileName, entry.title)
    if (result.imported) store.update { state ->
        val parent = entry.sourceBook?.takeIf { it.isWenku && state.books.any { saved -> saved.book.ref == it } }
        if (parent == null) state else state.withVolumeParent(result.ref.key, parent.key)
    }
    return result.ref
}
