package cc.novelia.app.data.backup

import cc.novelia.app.data.catalog.KeywordStore
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.LocalDocument
import cc.novelia.app.data.storage.LocalStore
import cc.novelia.app.data.storage.appJson
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/**
 * 阅读资料备份的业务编排层：导出快照，或先解包到暂存目录供预览，再显式执行合并恢复。
 * UI 只保留暂存 ID，归档字节和图片留在磁盘，避免大备份占用界面内存或依赖 Activity 存活。
 * 格式和完整性检查交给 LibraryBackupArchive，最终状态提交通过 LocalStore 的恢复边界完成。
 */
class LibraryBackupService(private val store: LocalStore, private val keywords: KeywordStore) {
    private val stagingRoot = File(store.context.filesDir, "backup-staging")

    suspend fun export(output: OutputStream, includeOriginals: Boolean) = withContext(Dispatchers.IO) {
        store.flush(); keywords.flush()
        val exportStage = File(store.exportsDir, "backup-${UUID.randomUUID()}").apply { mkdirs() }
        try {
        val work = coroutineContext
        val snapshot = store.state.value.forBackup()
        val files = linkedMapOf<String, File>()
        val documents = mutableListOf<String>(); val missing = mutableListOf<String>()
        localDocumentIds(snapshot).forEach { id ->
            work.ensureActive()
            require(id.matches(Regex("[a-zA-Z0-9-]{1,128}"))) { "本地文档标识无效，无法备份" }
            val source = File(store.documentsDir, "$id.json")
            if (!source.isFile) { missing += id; return@forEach }
            val document = store.document(id) { work.ensureActive() }
            // 归档保持自包含的版本 1 格式，旧版应用也可读取。
            val portableDocument = File(exportStage, "$id.json").apply { writeText(appJson.encodeToString(document), Charsets.UTF_8) }
            documents += id; files["documents/$id.json"] = portableDocument
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
        // 导入时从各文档的解析结果重建封面路径，不沿用其他设备的私有路径。
        val portable = snapshot.copy(books = snapshot.books.map { saved ->
            if (saved.book.ref.isLocal) saved.copy(book = saved.book.copy(cover = null)) else saved
        })
        val vocabulary = keywords.exportLibrary()
        val manifest = LibraryBackupManifest("novelia-library", 1, System.currentTimeMillis(), portable, documents,
            missing, includeOriginals, vocabulary.entries, files.mapValues { LibraryBackupArchive.digest(it.value) { work.ensureActive() } }, vocabulary.categories)
        LibraryBackupArchive.write(output, manifest, files) { work.ensureActive() }
        } finally { exportStage.deleteRecursively() }
    }

    /** 准备预览只写新建暂存目录，不修改现有书库；解包或校验失败时清理这次暂存内容。 */
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

    /**
     * 重新校验暂存归档后逐本文档安装：完整相同的文档可复用，否则分配新 ID 避免覆盖。
     * ID 映射同时用于进度、笔记和分卷关系。书库提交前失败只回收本次安装的文件；
     * 提交后即使标签词典保存失败也保留阅读资料，并返回提示供用户稍后重试。
     */
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
                    // 即使备份整个书库，内存中也只保留一本已解析的书。
                    val document = appJson.decodeFromString<LocalDocument>(File(directory, "documents/$sourceId.json").readText(Charsets.UTF_8))
                    val indexedId = if (document.sourceHash.isNotBlank()) store.findDocumentByHash(document.sourceHash) { work.ensureActive() }?.id else null
                    val candidateIds = sequenceOf(indexedId).filterNotNull() + current.books.asSequence()
                        .filter { it.book.ref.isLocal && it.book.ref.id != indexedId }.map { it.book.ref.id }
                    // 受保护的损坏副本可能与之后修复的副本具有相同源文件哈希；
                    // 优先使用完整副本，避免反复创建新的恢复书目。
                    val old = candidateIds.mapNotNull { candidate ->
                        work.ensureActive()
                        val index = runCatching { store.documentIndex(candidate) }.getOrNull() ?: return@mapNotNull null
                        val sameSource = if (document.sourceHash.isNotBlank()) index.sourceHash == document.sourceHash else index.name == document.name
                        if (!sameSource) return@mapNotNull null
                        try { store.document(candidate) { work.ensureActive() } }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                    }.firstOrNull { candidate ->
                        candidate.format == document.format && candidate.chapters == document.chapters && candidate.coverImage == document.coverImage &&
                        document.chapters.flatMap { it.paragraphs }.filter { it.startsWith("novelia-image:") }
                            .map { it.removePrefix("novelia-image:") }.plus(listOfNotNull(document.coverImage)).distinct().all { hash ->
                                matchesBackupAsset(store.documentImage(candidate.id, hash), manifest.assets["documents/$sourceId-images/$hash"]) { work.ensureActive() }
                            }
                    }
                    if (old != null) {
                        val retained = old
                        mapping[sourceId] = retained.id; covers[retained.id] = document.coverImage
                        // 后续备份可以补回首次恢复时缺少的原始文件。
                        listOf("epub", "txt", "srt").forEach { format ->
                            work.ensureActive()
                            val path = "documents/$sourceId.$format"
                            if (path in manifest.assets) {
                                val target = store.documentSource(retained.id, format)
                                installMissingBackupFile(File(directory, path), target, installed) { work.ensureActive() }
                                require(target.isFile) { "原文件恢复路径不可用，请检查存储空间后重试" }
                            }
                        }
                        return@forEach
                    }
                    // 重新生成 ID，即使归档来自本机，也不会覆盖现有文档。
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
                // 越过此边界后，即使页面关闭，也必须完成磁盘提交结果的报告。
                withContext(NonCancellable) {
                    store.commitRestore { latest -> mergeLibraryBackup(latest, imported) }
                    committed = true
                    // 标签目录写入失败不影响已经提交成功的书库；
                    // 保留暂存数据供重试，合并可重复执行且已有翻译优先。
                    val catalogueError = runCatching { keywords.mergeLibrary(manifest.keywordLibrary()); keywords.flush() }.exceptionOrNull()
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

private fun installMissingBackupFile(source: File, target: File, installed: MutableList<File>, checkCancelled: () -> Unit) {
    if (target.exists()) return
    val pending = File.createTempFile("restore-original-", ".tmp", target.parentFile)
    try {
        source.inputStream().use { input -> pending.outputStream().use { output ->
            val buffer = ByteArray(65536)
            while (true) { checkCancelled(); val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count) }
            output.fd.sync()
        } }
        checkCancelled()
        try {
            // 同目录移动仅发布完整文件，且不替换已经存在的原始文件。
            Files.move(pending.toPath(), target.toPath())
            installed += target
        } catch (_: FileAlreadyExistsException) { /* 另一项已完成的操作已经补齐原始文件。 */ }
    } finally { pending.delete() }
}

private fun matchesBackupAsset(file: File, expected: BackupAsset?, checkCancelled: () -> Unit): Boolean {
    if (!file.isFile || expected == null) return false
    return try { LibraryBackupArchive.digest(file, checkCancelled) == expected }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }
}

/** 用恢复后的本地 ID 重写所有关联键，并重建本机封面路径，防止沿用另一设备的绝对路径。 */
internal fun remapBackupLibrary(source: LibraryState, localIds: Map<String, String>, cover: (String) -> String?): LibraryState {
    fun key(value: String): String = if (value.startsWith("local/")) "local/${localIds[value.removePrefix("local/")] ?: value.removePrefix("local/")}" else value
    return source.forBackup().copy(
        books = source.books.map { saved ->
            val ref = if (saved.book.ref.isLocal) BookRef("local", localIds[saved.book.ref.id] ?: saved.book.ref.id) else saved.book.ref
            saved.copy(book = saved.book.copy(ref = ref, cover = if (ref.isLocal) cover(ref.id) else saved.book.cover?.takeIf { it.startsWith("https://") || it.startsWith("http://") }),
                parentWenkuKey = saved.parentWenkuKey?.let(::key), volumeOrder = saved.volumeOrder.map(::key))
        },
        positions = source.positions.mapKeys { key(it.key) }, notes = source.notes.map { it.copy(key = key(it.key)) },
        readingHistory = source.readingHistory.mapKeys { key(it.key) }.mapValues { (_, entry) -> entry.copy(bookKey = key(entry.bookKey)) },
        bookSettings = source.bookSettings.mapKeys { key(it.key) }, personalGlossaries = source.personalGlossaries.mapKeys { key(it.key) },
        blockedBooks = source.blockedBooks.map(::key).toSet()
    )
}
