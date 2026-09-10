package cc.novelia.app.data

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

val appJson = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true; explicitNulls = false }
fun hashName(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

/** Small user state is atomically persisted; chapter/document payloads live in separate files. */
class LocalStore(val context: Context) {
    private val stateFile = AtomicFile(File(context.filesDir, "library.json"))
    private val mutable = MutableStateFlow(runCatching { appJson.decodeFromString<LibraryState>(stateFile.openRead().bufferedReader().use { it.readText() }) }.getOrDefault(LibraryState()))
    val state = mutable.asStateFlow()
    val cacheDir = File(context.filesDir, "chapters").apply { mkdirs() }
    val documentsDir = File(context.filesDir, "documents").apply { mkdirs() }
    val metadataDir = File(context.filesDir, "metadata").apply { mkdirs() }
    val downloadsDir = File(context.filesDir, "downloads").apply { mkdirs() }
    val exportsDir = File(context.filesDir, "exports").apply { mkdirs() }
    @Synchronized fun update(transform: (LibraryState) -> LibraryState) {
        val next = transform(mutable.value)
        val output = stateFile.startWrite()
        try { output.write(appJson.encodeToString(next).toByteArray(Charsets.UTF_8)); stateFile.finishWrite(output); mutable.value = next }
        catch (error: Exception) { stateFile.failWrite(output); throw error }
    }
    fun saveBook(book: BookCard, folder: String = "默认收藏") = update { current ->
        current.copy(books = current.books.filterNot { it.book.ref == book.ref } + (current.books.find { it.book.ref == book.ref }?.copy(book = book, folder = folder) ?: SavedBook(book, folder)))
    }
    fun removeBook(ref: BookRef) = update { it.copy(books = it.books.filterNot { b -> b.book.ref == ref }) }
    fun rememberSearch(query: String) { if (query.isNotBlank()) update { it.copy(recentSearches = (listOf(query) + it.recentSearches.filterNot { old -> old == query }).take(20)) } }
    fun savePosition(ref: BookRef, position: Position) { if (!state.value.historyPaused) update { it.copy(positions = it.positions + (ref.key to position)) } }
    fun chapterFile(ref: BookRef, chapter: String) = File(cacheDir, hashName("${ref.key}/$chapter") + ".json")
    fun cachedChapter(ref: BookRef, chapter: String): Chapter? = runCatching { appJson.decodeFromString<Chapter>(chapterFile(ref, chapter).readText()) }.getOrNull()
    fun cacheChapter(ref: BookRef, id: String, chapter: Chapter) = atomicText(chapterFile(ref, id), appJson.encodeToString(chapter))
    fun saveDocument(document: LocalDocument) {
        document.images.forEach { (hash, data) -> documentImage(document.id, hash).apply { parentFile?.mkdirs() }.writeBytes(java.util.Base64.getDecoder().decode(data)) }
        atomicText(File(documentsDir, "${document.id}.json"), appJson.encodeToString(document.copy(images = emptyMap())))
    }
    fun documentImage(id: String, hash: String): File { require(hash.matches(Regex("[a-f0-9]{64}"))); return File(documentsDir, "${safeId(id)}-images/$hash") }
    fun documentSource(id: String, format: String): File { require(format in listOf("epub", "txt", "srt")); return File(documentsDir, "${safeId(id)}.$format") }
    fun document(id: String): LocalDocument = appJson.decodeFromString(File(documentsDir, "${safeId(id)}.json").readText())
    fun removeDocument(id: String) { File(documentsDir, "${safeId(id)}.json").delete(); File(documentsDir, "${safeId(id)}-images").listFiles()?.forEach { it.delete() }; File(documentsDir, "${safeId(id)}-images").delete(); listOf("epub", "txt", "srt").forEach { documentSource(id, it).delete() }; removeBook(BookRef("local", id)) }
    fun cacheSize(): Long = (cacheDir.listFiles()?.sumOf { it.length() } ?: 0) + (metadataDir.listFiles()?.sumOf { it.length() } ?: 0)
    fun clearCache() { (cacheDir.listFiles()?.toList().orEmpty() + metadataDir.listFiles()?.toList().orEmpty()).filter { it.isFile }.forEach { it.delete() } }
    private fun safeId(id: String): String { require(id.matches(Regex("[a-zA-Z0-9-]+"))); return id }
    private fun atomicText(file: File, text: String) {
        val atomic = AtomicFile(file); val stream = atomic.startWrite()
        try { stream.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) } catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
}
