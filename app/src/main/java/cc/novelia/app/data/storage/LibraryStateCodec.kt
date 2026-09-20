package cc.novelia.app.data.storage

import cc.novelia.app.data.model.Article
import cc.novelia.app.data.model.LibraryState
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Immutable payloads let progress updates persist only the small catalogue, without rewriting prose. */
internal class LibraryStateCodec(
    private val directory: File,
    private val read: (File) -> String,
    private val write: (File, String) -> Unit
) {
    @Serializable private data class LongText(val articles: List<Article>, val drafts: Map<String, String>)
    private var previous: LongText? = null
    private var previousHash: String? = null
    private var fallbackHash: String? = null
    private var needsCompaction = false

    fun encode(state: LibraryState, forcePayloadWrite: Boolean = false): String {
        val payload = LongText(state.savedArticles, state.drafts)
        val hash = if (!forcePayloadWrite && payload == previous) requireNotNull(previousHash) else {
            val text = appJson.encodeToString(payload)
            val name = hashName(text)
            directory.mkdirs()
            // Rewrite an existing hash too: a prior corrupt payload must be repairable by restore.
            write(file(name), text)
            fallbackHash = previousHash
            previous = payload; previousHash = name
            needsCompaction = true
            name
        }
        val small = state.copy(savedArticles = state.savedArticles.map { it.copy(content = "") }, drafts = emptyMap())
        return JsonObject(appJson.encodeToJsonElement(LibraryState.serializer(), small).jsonObject +
            (PAYLOAD to JsonPrimitive(hash))).toString()
    }

    fun decode(text: String): LibraryState {
        val json = appJson.parseToJsonElement(text).jsonObject
        val state = appJson.decodeFromJsonElement(LibraryState.serializer(), json)
        val hash = json[PAYLOAD]?.jsonPrimitive?.content ?: return state // Existing library.json remains readable.
        val payloadText = read(file(hash))
        require(hashName(payloadText) == hash) { "文章或草稿文件校验失败" }
        val payload = appJson.decodeFromString<LongText>(payloadText)
        previous = payload; previousHash = hash
        return state.copy(savedArticles = payload.articles, drafts = payload.drafts)
    }

    /** Called only after both state copies are committed; keep one prior payload for recovery. */
    fun compact() {
        if (!needsCompaction) return
        val keep = setOfNotNull(previousHash, fallbackHash)
        directory.listFiles()?.filter { it.isFile && it.extension == "json" && it.nameWithoutExtension !in keep }
            ?.forEach { it.delete() }
        needsCompaction = false
    }

    private fun file(hash: String): File {
        require(hash.matches(Regex("[a-f0-9]{64}"))) { "文章或草稿文件索引无效" }
        return File(directory, "$hash.json")
    }

    private companion object { const val PAYLOAD = "localLongTextPayload" }
}
