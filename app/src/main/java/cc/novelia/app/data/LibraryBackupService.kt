package cc.novelia.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** All archive IO is staged on disk; UI state retains only the opaque staging ID. */
class LibraryBackupService(private val store: LocalStore, private val keywords: KeywordStore) {
    private val stagingRoot = File(store.context.filesDir, "backup-staging")

    suspend fun export(output: OutputStream, includeOriginals: Boolean) = withContext(Dispatchers.IO) {
        store.flush(); keywords.flush()
        val work = coroutineContext
        val snapshot = store.state.value.forBackup()
        val files = linkedMapOf<String, File>()
        val documents = mutableListOf<String>(); val missing = mutableListOf<String>()
        localDocumentIds(snapshot).forEach { id ->
            work.ensureActive()
            require(id.matches(Regex("[a-zA-Z0-9-]{1,128}"))) { "本地文档标识无效，无法备份" }
            val source = File(store.documentsDir, "$id.json")
            if (!source.isFile) { missing += id; return@forEach }
            val document = store.document(id)
            documents += id; files["documents/$id.json"] = source
            val images = document.chapters.flatMap { it.paragraphs }.filter { it.startsWith("novelia-image:") }.map { it.removePrefix("novelia-image:") }.toSet() + listOfNotNull(document.coverImage)
            images.forEach { hash ->
                val image = store.documentImage(id, hash)
                require(image.isFile) { "《${document.name}》的插图缺失，请重新导入后再备份" }
                files["documents/$id-images/$hash"] = image
            }
            if (includeOriginals) listOf("epub", "txt", "srt").forEach { format ->
                store.documentSource(id, format).takeIf { it.isFile }?.let { files["documents/$id.$format"] = it }
            }
        }
        // Device-specific cover paths are reconstructed from each parsed document on import.
        val portable = snapshot.copy(books = snapshot.books.map { saved ->
            if (saved.book.ref.isLocal) saved.copy(book = saved.book.copy(cover = null)) else saved
        })
        val manifest = LibraryBackupManifest("novelia-library", 1, System.currentTimeMillis(), portable, documents,
            missing, includeOriginals, keywords.exportSnapshot(), files.mapValues { LibraryBackupArchive.digest(it.value) { work.ensureActive() } })
        LibraryBackupArchive.write(output, manifest, files) { work.ensureActive() }
    }

    suspend fun prepare(input: InputStream): BackupPreview = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString(); val directory = staging(id).apply { mkdirs() }
        val work = coroutineContext
        try { previewOf(id, LibraryBackupArchive.extract(input, directory) { work.ensureActive() }) }
        catch (error: Exception) { directory.deleteRecursively(); throw error }
    }

    suspend fun preview(id: String): BackupPreview = withContext(Dispatchers.IO) {
        val work = coroutineContext
        previewOf(id, LibraryBackupArchive.validate(staging(id)) { work.ensureActive() })
    }

    suspend fun discard(id: String) = withContext(Dispatchers.IO) { staging(id).deleteRecursively(); Unit }

    suspend fun restore(id: String) = withContext(Dispatchers.IO) {
        restoreLock.withLock {
            val work = coroutineContext
            val directory = staging(id)
            val manifest = LibraryBackupArchive.validate(directory) { work.ensureActive() }
            val current = store.state.value
            val mapping = linkedMapOf<String, String>()
            val covers = mutableMapOf<String, String?>()
            val installed = mutableListOf<File>()
            var committed = false
            try {
                manifest.documents.forEach { sourceId ->
                    work.ensureActive()
                    // Keep only one parsed book in memory, even for a library-sized backup.
                    val document = appJson.decodeFromString<LocalDocument>(File(directory, "documents/$sourceId.json").readText(Charsets.UTF_8))
                    val existingId = if (document.sourceHash.isNotBlank()) store.findDocumentByHash(document.sourceHash) { work.ensureActive() }?.id
                        else current.books.asSequence().filter { it.book.ref.isLocal }.mapNotNull { runCatching { store.document(it.book.ref.id) }.getOrNull() }
                            .firstOrNull { it.name == document.name && it.chapters == document.chapters }?.id
                    val old = existingId?.let { runCatching { store.document(it) }.getOrNull() }
                    val same = old != null && old.format == document.format && old.chapters == document.chapters && old.coverImage == document.coverImage &&
                        document.chapters.flatMap { it.paragraphs }.filter { it.startsWith("novelia-image:") }
                            .map { it.removePrefix("novelia-image:") }.plus(listOfNotNull(document.coverImage)).all { store.documentImage(old.id, it).isFile }
                    if (same) { mapping[sourceId] = old!!.id; covers[old.id] = document.coverImage; return@forEach }
                    // Fresh IDs eliminate overwrites even when the archive came from this device.
                    val targetId = UUID.randomUUID().toString(); mapping[sourceId] = targetId; covers[targetId] = document.coverImage
                    manifest.assets.keys.filter { it == "documents/$sourceId.json" || it.startsWith("documents/$sourceId-images/") || it in listOf("documents/$sourceId.epub", "documents/$sourceId.txt", "documents/$sourceId.srt") }.forEach { path ->
                        work.ensureActive()
                        val relative = path.removePrefix("documents/").replaceFirst(sourceId, targetId)
                        val target = File(store.documentsDir, relative)
                        require(!target.exists()) { "恢复目标意外存在，请重试" }
                        target.parentFile?.mkdirs(); installed += target
                        if (path.endsWith(".json")) target.outputStream().use { output ->
                            output.write(appJson.encodeToString(document.copy(id = targetId)).toByteArray(Charsets.UTF_8)); output.fd.sync()
                        }
                        else File(directory, path).inputStream().use { input -> target.outputStream().use { output ->
                            val buffer = ByteArray(65536)
                            while (true) { work.ensureActive(); val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count) }
                            output.fd.sync()
                        } }
                    }
                }
                val imported = remapBackupLibrary(manifest.library, mapping) { documentId ->
                    covers[documentId]?.let { store.documentImage(documentId, it).absolutePath }
                }
                work.ensureActive()
                // Past this boundary we must finish reporting the disk commit, even if the screen closes.
                withContext(NonCancellable) {
                    store.commitRestore { latest -> mergeLibraryBackup(latest, imported) }
                    committed = true
                    // A catalogue failure does not invalidate the successfully committed library.
                    // Keep the stage for a retry; merge is idempotent and existing translations win.
                    val catalogueError = runCatching { keywords.mergeSnapshot(manifest.keywords); keywords.flush() }.exceptionOrNull()
                    if (catalogueError == null) directory.deleteRecursively()
                    if (catalogueError == null) null else "阅读资料已恢复，但标签词典暂未保存；请保留备份并稍后重试。"
                }
            } finally {
                if (!committed) installed.asReversed().forEach { file ->
                    file.delete()
                    file.parentFile?.takeIf { it != store.documentsDir && it.list().orEmpty().isEmpty() }?.delete()
                }
            }
        }
    }

    private fun staging(id: String): File {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "恢复暂存标识无效，请重新选择备份" }
        return File(stagingRoot, id)
    }
    private fun previewOf(id: String, manifest: LibraryBackupManifest) = BackupPreview(id, manifest.createdAt,
        manifest.library.books.size, manifest.library.positions.size, manifest.library.notes.size, manifest.documents.size,
        manifest.missingDocuments.size, manifest.keywords.size, manifest.assets.values.sumOf { it.bytes }, manifest.originalsIncluded)

    private companion object { val restoreLock = Mutex() }
}

internal fun remapBackupLibrary(source: LibraryState, localIds: Map<String, String>, cover: (String) -> String?): LibraryState {
    fun key(value: String): String = if (value.startsWith("local/")) "local/${localIds[value.removePrefix("local/")] ?: value.removePrefix("local/")}" else value
    return source.forBackup().copy(
        books = source.books.map { saved ->
            val ref = if (saved.book.ref.isLocal) BookRef("local", localIds[saved.book.ref.id] ?: saved.book.ref.id) else saved.book.ref
            saved.copy(book = saved.book.copy(ref = ref, cover = if (ref.isLocal) cover(ref.id) else saved.book.cover?.takeIf { it.startsWith("https://") || it.startsWith("http://") }),
                parentWenkuKey = saved.parentWenkuKey?.let(::key), volumeOrder = saved.volumeOrder.map(::key))
        },
        positions = source.positions.mapKeys { key(it.key) }, notes = source.notes.map { it.copy(key = key(it.key)) },
        bookSettings = source.bookSettings.mapKeys { key(it.key) }, personalGlossaries = source.personalGlossaries.mapKeys { key(it.key) },
        blockedBooks = source.blockedBooks.map(::key).toSet()
    )
}
