package cc.novelia.app.data.storage

import android.content.Context
import android.util.AtomicFile
import cc.novelia.app.data.cache.ChapterCacheIndex
import cc.novelia.app.data.cache.MetadataCache
import cc.novelia.app.data.cache.WeightedMemoryCache
import cc.novelia.app.data.chapters.ChapterRequests
import cc.novelia.app.data.chapters.clearChapterFreshness
import cc.novelia.app.data.documents.DocumentHashIndex
import cc.novelia.app.data.documents.DocumentStorage
import cc.novelia.app.data.library.withoutBook
import cc.novelia.app.data.library.withReadingPosition
import cc.novelia.app.data.library.withMigratedReadingHistory
import cc.novelia.app.data.model.withStableFolderIds
import cc.novelia.app.data.webdav.WebDavProjection
import cc.novelia.app.data.updates.withSavedBook
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.Chapter
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.LocalChapter
import cc.novelia.app.data.model.LocalDocument
import cc.novelia.app.data.model.LocalReadingContent
import cc.novelia.app.data.model.Position
import cc.novelia.app.files.DocumentTools
import cc.novelia.app.files.EPUB_CONTENT_VERSION
import cc.novelia.app.files.contentMode
import cc.novelia.app.files.recoverEpubChapter
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/**
 * 本地书库的状态入口：内存中发布不可变 [LibraryState]，磁盘中原子保存小型状态快照。
 * 章节、文档及文章/草稿正文分开存储，避免每次阅读进度变化都重写整本书。
 *
 * 修改状态应调用 [update]，需要等待保存完成的边界调用 [flush]。同步文件方法可能执行
 * 磁盘 IO，调用方应安排在后台线程。主状态损坏时进入保护模式，只有显式恢复可以解除。
 */
class LocalStore(val context: Context, private val syncDevicePreferences: () -> Boolean = { false }) {
    private val stateFile = AtomicFile(File(context.filesDir, "library.json"))
    private val lastGoodFile = File(context.filesDir, "library-last-good.json")
    private val stateCodec = LibraryStateCodec(File(context.filesDir, "library-text"),
        { AtomicFile(it).openRead().bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() } }, ::atomicText)
    private val initial = loadLibraryState(
        exists = File(context.filesDir, "library.json").exists() || File(context.filesDir, "library.json.bak").exists() ||
            lastGoodFile.exists() || File(lastGoodFile.path + ".bak").exists(),
        read = { stateFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() } },
        readLastGood = { AtomicFile(lastGoodFile).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() } },
        decode = stateCodec::decode
    )
    private val mutable = MutableStateFlow(initial.state.withMigratedReadingHistory().withStableFolderIds())
    private val mutableRecoveryIssue = MutableStateFlow(initial.issue)
    // 损坏快照仍可能包含可恢复的引用，应保留其历史正文载荷。
    private val preserveTextHistory = initial.issue != null || context.filesDir.listFiles().orEmpty().any { it.name.startsWith("library-damaged-") }
    val recoveryIssue = mutableRecoveryIssue.asStateFlow()
    private data class Revision(val number: Long, val state: LibraryState)
    private var revision = 0L
    private val diskLock = Any()
    private val persistence = StatePersistence<Revision>(CoroutineScope(SupervisorJob() + Dispatchers.IO)) { next ->
        synchronized(diskLock) {
            // 恢复提交后，原子提交之前排队的全部旧快照失效。
            val eligible = synchronized(this) { next.number == revision && mutableRecoveryIssue.value == null }
            // 普通界面状态更新无需等待 JSON 编码或文件系统写入。
            if (eligible) writeState(next.state)
        }
    }
    val state = mutable.asStateFlow()
    val persistenceError = persistence.error
    val cacheDir = File(context.filesDir, "chapters").apply { mkdirs() }
    val documentsDir = File(context.filesDir, "documents").apply { mkdirs() }
    val metadataDir = File(context.filesDir, "metadata").apply { mkdirs() }
    val metadataCache = MetadataCache(metadataDir)
    val downloadsDir = File(context.filesDir, "downloads").apply { mkdirs() }
    val exportsDir = File(context.filesDir, "exports").apply { mkdirs() }
    private val chapterLock = Any()
    private val documentLock = Any()
    private val checkedDocumentModes = mutableSetOf<String>()
    private val chapterMemory = WeightedMemoryCache<String, Chapter>(24, 12L * 1024 * 1024, ::chapterWeight)
    private val documentMemory = WeightedMemoryCache<String, LocalDocument>(4, 32L * 1024 * 1024, ::documentWeight)
    private val localChapterMemory = WeightedMemoryCache<String, LocalChapter>(12, 12L * 1024 * 1024) { 64 + paragraphsWeight(it.paragraphs) + localContentWeight(it.readingContent) }
    private val documentStorage = DocumentStorage(documentsDir,
        { AtomicFile(it).openRead().bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() } }, ::atomicText)
    private val sourceIndex by lazy {
        val file = File(documentsDir, "source-index.json")
        val initial = runCatching { appJson.decodeFromString<Map<String, String>>(AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }) }.getOrDefault(emptyMap())
        DocumentHashIndex(initial, { documentIndex(it).sourceHash }, { atomicText(file, appJson.encodeToString(it)) })
    }
    private val chapterIndex = ChapterCacheIndex(cacheDir, 256L * 1024 * 1024)
    private val mutableCacheGeneration = MutableStateFlow(0L)
    val cacheGeneration = mutableCacheGeneration.asStateFlow()
    val chapterRequests = ChapterRequests(this)

    /**
     * 在同一把锁内读取、变换并发布状态，防止并发操作基于旧快照覆盖彼此。
     * [transform] 应只计算新状态；编码和文件写入由单写入器异步处理。
     * 相同状态不触发写入，恢复保护期间直接忽略修改，界面可通过 [recoveryIssue] 提示用户。
     */
    @Synchronized fun update(transform: (LibraryState) -> LibraryState) {
        // 调用方包括界面回调和阅读器销毁流程；恢复保护期间保持状态只读，
        // 避免在这些回调中抛异常，由持续显示的恢复提示说明原因。
        if (mutableRecoveryIssue.value != null) return
        val before = mutable.value
        val raw = transform(before).withMigratedReadingHistory().withStableFolderIds()
        val domains = WebDavProjection.affectedLibraryDomains(before, raw)
        val includeDevices = syncDevicePreferences()
        val next = if(domains.isEmpty()) raw.copy(syncReplica = before.syncReplica) else raw.copy(syncReplica = before.syncReplica.track(
            WebDavProjection.library(before, domains, includeDevices), WebDavProjection.library(raw, domains, includeDevices), domains))
        if (next == mutable.value) return
        persistence.submit(Revision(++revision, next))
        mutable.value = next
    }

    /** 同步落地保留远端版本，不将它识别为新的用户编辑；变换仍须在最新本机状态上合并。 */
    @Synchronized internal fun updateFromWebDav(transform: (LibraryState) -> LibraryState) {
        check(mutableRecoveryIssue.value == null) { "请先恢复受保护的本地资料" }
        val next = transform(mutable.value).withStableFolderIds()
        if(next == mutable.value) return
        persistence.submit(Revision(++revision, next))
        mutable.value = next
    }

    private fun writeState(next: LibraryState, forcePayloadWrite: Boolean = false) {
        val text = stateCodec.encode(next, forcePayloadWrite)
        val output = stateFile.startWrite()
        try { output.write(text.toByteArray(Charsets.UTF_8)); stateFile.finishWrite(output) }
        catch (error: Exception) { stateFile.failWrite(output); throw error }
        // 备用副本尽力写入，其失败不能把已成功提交的书库误报为失败。
        runCatching { atomicText(lastGoodFile, text); if (!preserveTextHistory) stateCodec.compact() }
    }

    /**
     * 恢复操作的提交边界：排空旧写入、保存损坏文件副本，再写盘并发布恢复后的状态。
     * NonCancellable 使提交阶段不会因离开页面而中断；调用前应完成耗时的解析和校验。
     * 磁盘锁先于状态锁获取，且递增 revision，使提交前排队的旧快照不能覆盖恢复结果。
     */
    internal suspend fun commitRestore(transform: (LibraryState) -> LibraryState) = withContext(Dispatchers.IO + NonCancellable) {
        persistence.flush()
        synchronized(diskLock) {
            synchronized(this@LocalStore) {
                val before = mutable.value
                val restored = transform(before).withMigratedReadingHistory().withStableFolderIds()
                val domains = WebDavProjection.affectedLibraryDomains(before, restored)
                val includeDevices = syncDevicePreferences()
                val next = restored.copy(syncReplica = before.syncReplica.track(WebDavProjection.library(before, domains, includeDevices),
                    WebDavProjection.library(restored, domains, includeDevices), domains))
                if (mutableRecoveryIssue.value != null) {
                    val damaged = File(context.filesDir, "library.json")
                    if (damaged.exists()) damaged.copyTo(File(context.filesDir, "library-damaged-${java.util.UUID.randomUUID()}.json"))
                }
                // 显式恢复会修复正文载荷，即使解码结果与进程内缓存相同。
                writeState(next, forcePayloadWrite = true)
                revision += 1
                mutable.value = next
                mutableRecoveryIssue.value = null
            }
        }
    }

    suspend fun recoverLastGood() {
        require(recoveryIssue.value?.hasLastGood == true) { "没有可用的最后良好副本，请选择备份文件恢复" }
        commitRestore { it }
    }

    /** 需要可靠落盘的生命周期和后台任务边界应等待此方法完成。 */
    suspend fun flush() = withContext(Dispatchers.IO) { persistence.flush() }

    fun saveBook(book: BookCard, folder: String = "默认收藏") = update { it.withSavedBook(book, folder) }
    fun removeBook(ref: BookRef) = update { it.withoutBook(ref) }
    fun rememberSearch(query: String) { if (query.isNotBlank()) update { it.copy(recentSearches = (listOf(query) + it.recentSearches.filterNot { old -> old == query }).take(20)) } }
    fun savePosition(ref: BookRef, position: Position, bookTitle: String = "") = update { it.withReadingPosition(ref, position, bookTitle) }
    fun chapterFile(ref: BookRef, chapter: String) = File(cacheDir, hashName("${ref.key}/$chapter") + ".json")
    /** 优先复用已解码章节；缺失或损坏的磁盘缓存按未命中处理，交由上层决定联网或提示。 */
    fun cachedChapter(ref: BookRef, chapter: String): Chapter? = synchronized(chapterLock) {
        val file = chapterFile(ref, chapter)
        chapterMemory[file.name]?.let { chapterIndex.accessed(file); return@synchronized it }
        runCatching {
            appJson.decodeFromString<Chapter>(AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
        }.getOrNull()?.also { chapterMemory.put(file.name, it); chapterIndex.accessed(file) }
    }

    fun cacheChapter(ref: BookRef, id: String, chapter: Chapter) = synchronized(chapterLock) {
        val file = chapterFile(ref, id)
        atomicText(file, appJson.encodeToString(chapter))
        chapterMemory.put(file.name, chapter)
        chapterIndex.written(file).forEach(chapterMemory::remove)
    }

    /**
     * 将导入文档拆为图片文件、章节正文和轻量目录，并更新源文件哈希索引。
     * 保存文档本身不等于加入书架；调用方还需提交对应书目状态。
     * [checkCancelled] 让大文档写入能在分段处理时响应取消。
     */
    fun saveDocument(document: LocalDocument, checkCancelled: () -> Unit = {}) = synchronized(documentLock) {
        check(recoveryIssue.value == null) { "本地资料已保护，请先前往资料备份与恢复" }
        val id = safeId(document.id)
        document.images.forEach { (hash, data) -> documentImage(document.id, hash).apply { parentFile?.mkdirs() }.writeBytes(java.util.Base64.getDecoder().decode(data)) }
        val stored = document.copy(images = emptyMap())
        val index = documentStorage.save(stored, checkCancelled)
        documentMemory.put(id, index)
        localChapterMemory.clear()
        sourceIndex.record(id, stored.sourceHash)
    }
    fun findDocumentByHash(hash: String, checkCancelled: () -> Unit = {}): BookRef? = synchronized(documentLock) {
        sourceIndex.find(hash, state.value.books.mapNotNull { it.book.ref.takeIf(BookRef::isLocal)?.id }, checkCancelled)?.let { BookRef("local", it) }
    }
    fun documentImage(id: String, hash: String): File { require(hash.matches(Regex("[a-f0-9]{64}"))); return File(documentsDir, "${safeId(id)}-images/$hash") }
    fun documentSource(id: String, format: String): File { require(format in listOf("epub", "txt", "srt")); return File(documentsDir, "${safeId(id)}.$format") }
    /** 轻量章节目录，用于阅读导航、搜索规划和导出元数据。 */
    fun documentIndex(id: String): LocalDocument = synchronized(documentLock) {
        val key = safeId(id)
        documentMemory[key] ?: documentStorage.index(key).also { documentMemory.put(key, it) }
    }
    fun documentChapter(id: String, chapterId: String): LocalChapter = synchronized(documentLock) {
        val index = documentIndex(id)
        val key = "${index.id}/${index.chapterFiles[chapterId] ?: chapterId}"
        localChapterMemory[key] ?: recoverDocumentChapter(index, documentStorage.chapter(index, chapterId)).also {
            val current = documentIndex(id)
            localChapterMemory.put("${current.id}/${current.chapterFiles[chapterId] ?: chapterId}", it)
        }
    }

    internal fun recordDocumentDownloadMode(id: String, mode: String) = synchronized(documentLock) {
        if (recoveryIssue.value != null) return@synchronized
        val index = documentIndex(id)
        if (index.downloadMode == mode) return@synchronized
        documentMemory.put(id, documentStorage.recordDownloadMode(index, mode))
        localChapterMemory.clear()
    }

    /** 只在第一次读旧 EPUB 的当前章时解析原件；损坏或丢失原件不影响缓存正文阅读。 */
    private fun recoverDocumentChapter(document: LocalDocument, chapter: LocalChapter): LocalChapter {
        if (document.format != "epub" || document.chapterFiles.isEmpty() || recoveryIssue.value != null) return chapter
        val source = documentSource(document.id, "epub")
        if (!source.isFile) return chapter
        var index = document
        try {
            if (index.downloadMode == null && checkedDocumentModes.add(index.id) && index.sourceHash.isNotBlank()) {
                // 旧导入没有保存下载模式。只有完整文件哈希匹配，才沿用下载记录的参数。
                val match = state.value.downloads.firstOrNull { entry ->
                    val file = File(downloadsDir, entry.fileName)
                    entry.contentMode() != null && entry.fileName == file.name && file.isFile && file.length() == source.length() &&
                        runCatching { file.inputStream().use { DocumentTools.digest(it) } }.getOrNull() == index.sourceHash
                }
                match?.contentMode()?.let { mode ->
                    index = documentStorage.recordDownloadMode(index, mode)
                    documentMemory.put(index.id, index)
                }
            }
            if (chapter.epubContentVersion >= EPUB_CONTENT_VERSION && chapter.downloadMode == index.downloadMode) return chapter
            val restored = recoverEpubChapter(source, chapter, index.downloadMode)
            val updated = documentStorage.replaceChapter(index, restored)
            documentMemory.put(index.id, updated)
            update { it.withRecoveredEpubChapter(index.id, chapter, restored) }
            return restored
        } catch (_: Exception) {
            return chapter
        }
    }
    /** 可移植的完整文档；仅显式备份或导出时才应分配整本文档内存。 */
    fun document(id: String, checkCancelled: () -> Unit = {}): LocalDocument =
        documentStorage.full(documentIndex(id), checkCancelled)

    /** 名称写入轻量目录并同步书架；不重建正文，也不改变分卷关系和阅读记录。 */
    fun renameDocument(id: String, name: String) = synchronized(documentLock) {
        check(recoveryIssue.value == null) { "本地资料已保护，请先前往资料备份与恢复" }
        val key = safeId(id)
        val renamed = documentStorage.rename(documentIndex(key), name)
        documentMemory.put(key, renamed)
        val ref = BookRef("local", key)
        update { state -> state.copy(
            books = state.books.map { if(it.book.ref == ref) it.copy(book = it.book.copy(title = renamed.name)) else it },
            notes = state.notes.map { if(it.key == ref.key) it.copy(bookTitle = renamed.name) else it }
        ) }
    }

    fun removeDocument(id: String) = synchronized(documentLock) {
        check(recoveryIssue.value == null) { "本地资料已保护，请先前往资料备份与恢复" }
        val key = safeId(id)
        documentMemory.remove(key)
        localChapterMemory.clear()
        sourceIndex.remove(key)
        documentStorage.remove(key)
        File(documentsDir, "$key-images").listFiles()?.forEach { it.delete() }
        File(documentsDir, "$key-images").delete()
        listOf("epub", "txt", "srt").forEach { documentSource(key, it).delete() }
        removeBook(BookRef("local", key))
    }

    fun cacheSize(): Long = synchronized(chapterLock) {
        chapterIndex.size() + metadataCache.size()
    }

    /** 清除可重新获取的网络缓存，并取消旧代次请求；已导入的本地文档独立保存。 */
    fun clearCache() = synchronized(chapterLock) {
        chapterMemory.clear()
        cacheDir.listFiles()?.toList().orEmpty()
            .filter { it.isFile }.forEach { it.delete() }
        metadataCache.clear()
        chapterIndex.reset()
        clearChapterFreshness(this)
        mutableCacheGeneration.value += 1
        chapterRequests.invalidateBefore(mutableCacheGeneration.value)
    }

    /** 在清理缓存所用的同一把锁内检查代次并写入，避免清理前的慢响应重新填回缓存。 */
    fun <T> withCacheGeneration(generation: Long, block: () -> T): T? = synchronized(chapterLock) {
        if (generation == mutableCacheGeneration.value) block() else null
    }

    private fun safeId(id: String): String { require(id.matches(Regex("[a-zA-Z0-9-]+"))); return id }
    private fun atomicText(file: File, text: String) {
        val atomic = AtomicFile(file); val stream = atomic.startWrite()
        try { stream.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) } catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
}

private fun textWeight(text: String?): Long = if (text == null) 0 else 40L + text.length * 2L
private fun paragraphsWeight(paragraphs: List<String>?): Long = paragraphs?.sumOf { 8 + textWeight(it) } ?: 0
private fun localContentWeight(content: LocalReadingContent?): Long = if (content == null) 0 else
    64L + content.secondary.size * 24L + content.groups.sumOf { group ->
        96L + group.original.size * 24L + group.translations.sumOf { 32L + it.size * 24L }
    }
private fun chapterWeight(chapter: Chapter): Long = 128 + textWeight(chapter.titleJp) + textWeight(chapter.titleZh) +
    textWeight(chapter.novelTitleJp) + textWeight(chapter.novelTitleZh) +
    paragraphsWeight(chapter.paragraphs) + paragraphsWeight(chapter.youdaoParagraphs) +
    paragraphsWeight(chapter.gptParagraphs) + paragraphsWeight(chapter.sakuraParagraphs) + localContentWeight(chapter.localContent)
private fun documentWeight(document: LocalDocument): Long = 128 + textWeight(document.name) +
    document.chapters.sumOf { 64 + textWeight(it.id) + textWeight(it.title) + paragraphsWeight(it.paragraphs) + localContentWeight(it.readingContent) } +
    document.chapterFiles.entries.sumOf { 64 + textWeight(it.key) + textWeight(it.value) }
