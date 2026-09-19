package cc.novelia.app.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

val appJson = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true; explicitNulls = false }
fun hashName(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    val hex = "0123456789abcdef"
    return CharArray(digest.size * 2) { index ->
        val byte = digest[index / 2].toInt() and 0xff
        hex[if (index % 2 == 0) byte ushr 4 else byte and 0xf]
    }.concatToString()
}

/** Small user state is atomically persisted; chapter/document payloads live in separate files. */
class LocalStore(val context: Context) {
    private val stateFile = AtomicFile(File(context.filesDir, "library.json"))
    private val lastGoodFile = File(context.filesDir, "library-last-good.json")
    private val initial = loadLibraryState(
        exists = File(context.filesDir, "library.json").exists() || File(context.filesDir, "library.json.bak").exists() ||
            lastGoodFile.exists() || File(lastGoodFile.path + ".bak").exists(),
        read = { stateFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() } },
        readLastGood = { AtomicFile(lastGoodFile).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() } }
    )
    private val mutable = MutableStateFlow(initial.state)
    private val mutableRecoveryIssue = MutableStateFlow(initial.issue)
    val recoveryIssue = mutableRecoveryIssue.asStateFlow()
    private data class Revision(val number: Long, val state: LibraryState)
    private var revision = 0L
    private val diskLock = Any()
    private val persistence = StatePersistence<Revision>(CoroutineScope(SupervisorJob() + Dispatchers.IO)) { next ->
        synchronized(diskLock) {
            // A restore invalidates every queued snapshot created before its atomic commit.
            val eligible = synchronized(this) { next.number == revision && mutableRecoveryIssue.value == null }
            // UI updates never wait for normal JSON encoding or filesystem writes.
            if (eligible) writeState(next.state)
        }
    }
    val state = mutable.asStateFlow()
    val persistenceError = persistence.error
    val cacheDir = File(context.filesDir, "chapters").apply { mkdirs() }
    val documentsDir = File(context.filesDir, "documents").apply { mkdirs() }
    val metadataDir = File(context.filesDir, "metadata").apply { mkdirs() }
    val downloadsDir = File(context.filesDir, "downloads").apply { mkdirs() }
    val exportsDir = File(context.filesDir, "exports").apply { mkdirs() }
    private val chapterLock = Any()
    private val documentLock = Any()
    private val chapterMemory = WeightedMemoryCache<String, Chapter>(24, 12L * 1024 * 1024, ::chapterWeight)
    private val documentMemory = WeightedMemoryCache<String, LocalDocument>(4, 32L * 1024 * 1024, ::documentWeight)
    private val sourceIndex by lazy {
        val file = File(documentsDir, "source-index.json")
        val initial = runCatching { appJson.decodeFromString<Map<String, String>>(AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }) }.getOrDefault(emptyMap())
        DocumentHashIndex(initial, { document(it).sourceHash }, { atomicText(file, appJson.encodeToString(it)) })
    }
    private val chapterIndex = ChapterCacheIndex(cacheDir, 256L * 1024 * 1024)
    private val mutableCacheGeneration = MutableStateFlow(0L)
    val cacheGeneration = mutableCacheGeneration.asStateFlow()

    /** State changes are immediate; JSON encoding and atomic writes run on a single IO writer. */
    @Synchronized fun update(transform: (LibraryState) -> LibraryState) {
        // Callers include UI callbacks and reader disposal; keep the protected state read-only
        // instead of throwing from those callbacks. A persistent recovery banner explains this.
        if (mutableRecoveryIssue.value != null) return
        val next = transform(mutable.value)
        if (next == mutable.value) return
        persistence.submit(Revision(++revision, next))
        mutable.value = next
    }

    private fun writeState(next: LibraryState) {
        val text = appJson.encodeToString(next)
        val output = stateFile.startWrite()
        try { output.write(text.toByteArray(Charsets.UTF_8)); stateFile.finishWrite(output) }
        catch (error: Exception) { stateFile.failWrite(output); throw error }
        // A second copy is best effort: a failure must not misreport an already committed library.
        runCatching { atomicText(lastGoodFile, text) }
    }

    /** Only this explicit recovery boundary may replace a protected, unreadable library. */
    internal suspend fun commitRestore(transform: (LibraryState) -> LibraryState) = withContext(Dispatchers.IO + NonCancellable) {
        persistence.flush()
        synchronized(diskLock) {
            synchronized(this@LocalStore) {
                val next = transform(mutable.value)
                if (mutableRecoveryIssue.value != null) {
                    val damaged = File(context.filesDir, "library.json")
                    if (damaged.exists()) damaged.copyTo(File(context.filesDir, "library-damaged-${java.util.UUID.randomUUID()}.json"))
                }
                writeState(next)
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

    /** Await this at lifecycle and background-work boundaries that require durable state. */
    suspend fun flush() = withContext(Dispatchers.IO) { persistence.flush() }

    fun saveBook(book: BookCard, folder: String = "默认收藏") = update { current ->
        current.copy(books = current.books.filterNot { it.book.ref == book.ref } + (current.books.find { it.book.ref == book.ref }?.copy(book = book, folder = folder) ?: SavedBook(book, folder)))
    }
    fun removeBook(ref: BookRef) = update { it.withoutBook(ref) }
    fun rememberSearch(query: String) { if (query.isNotBlank()) update { it.copy(recentSearches = (listOf(query) + it.recentSearches.filterNot { old -> old == query }).take(20)) } }
    fun savePosition(ref: BookRef, position: Position) = update { if (it.historyPaused) it else it.copy(positions = it.positions + (ref.key to position)) }
    fun chapterFile(ref: BookRef, chapter: String) = File(cacheDir, hashName("${ref.key}/$chapter") + ".json")
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

    fun saveDocument(document: LocalDocument) = synchronized(documentLock) {
        check(recoveryIssue.value == null) { "本地资料已保护，请先前往资料备份与恢复" }
        val id = safeId(document.id)
        document.images.forEach { (hash, data) -> documentImage(document.id, hash).apply { parentFile?.mkdirs() }.writeBytes(java.util.Base64.getDecoder().decode(data)) }
        val stored = document.copy(images = emptyMap())
        atomicText(File(documentsDir, "$id.json"), appJson.encodeToString(stored))
        documentMemory.put(id, stored)
        sourceIndex.record(id, stored.sourceHash)
    }
    fun findDocumentByHash(hash: String, checkCancelled: () -> Unit = {}): BookRef? = synchronized(documentLock) {
        sourceIndex.find(hash, state.value.books.mapNotNull { it.book.ref.takeIf(BookRef::isLocal)?.id }, checkCancelled)?.let { BookRef("local", it) }
    }
    fun documentImage(id: String, hash: String): File { require(hash.matches(Regex("[a-f0-9]{64}"))); return File(documentsDir, "${safeId(id)}-images/$hash") }
    fun documentSource(id: String, format: String): File { require(format in listOf("epub", "txt", "srt")); return File(documentsDir, "${safeId(id)}.$format") }
    fun document(id: String): LocalDocument = synchronized(documentLock) {
        val key = safeId(id)
        documentMemory[key] ?: appJson.decodeFromString<LocalDocument>(
            AtomicFile(File(documentsDir, "$key.json")).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        ).also { documentMemory.put(key, it) }
    }

    fun removeDocument(id: String) = synchronized(documentLock) {
        check(recoveryIssue.value == null) { "本地资料已保护，请先前往资料备份与恢复" }
        val key = safeId(id)
        documentMemory.remove(key)
        sourceIndex.remove(key)
        File(documentsDir, "$key.json").delete()
        File(documentsDir, "$key-images").listFiles()?.forEach { it.delete() }
        File(documentsDir, "$key-images").delete()
        listOf("epub", "txt", "srt").forEach { documentSource(key, it).delete() }
        removeBook(BookRef("local", key))
    }

    fun cacheSize(): Long = synchronized(chapterLock) {
        chapterIndex.size() + (metadataDir.listFiles()?.sumOf { it.length() } ?: 0)
    }

    fun clearCache() = synchronized(chapterLock) {
        chapterMemory.clear()
        (cacheDir.listFiles()?.toList().orEmpty() + metadataDir.listFiles()?.toList().orEmpty())
            .filter { it.isFile }.forEach { it.delete() }
        chapterIndex.reset()
        clearChapterFreshness(this)
        mutableCacheGeneration.value += 1
    }

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
private fun chapterWeight(chapter: Chapter): Long = 128 + textWeight(chapter.titleJp) + textWeight(chapter.titleZh) +
    textWeight(chapter.novelTitleJp) + textWeight(chapter.novelTitleZh) +
    paragraphsWeight(chapter.paragraphs) + paragraphsWeight(chapter.youdaoParagraphs) +
    paragraphsWeight(chapter.gptParagraphs) + paragraphsWeight(chapter.sakuraParagraphs)
private fun documentWeight(document: LocalDocument): Long = 128 + textWeight(document.name) +
    document.chapters.sumOf { 64 + textWeight(it.id) + textWeight(it.title) + paragraphsWeight(it.paragraphs) }
