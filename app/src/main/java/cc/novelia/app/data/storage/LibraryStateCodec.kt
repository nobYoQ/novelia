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

/**
 * 将文章和草稿长文本拆成按内容哈希命名的不可变文件，主 JSON 只保存索引和轻量状态。
 * 正文没变时复用上一份载荷，使阅读进度等小修改不会产生大规模文本编码和写盘。
 * 该对象的缓存字段无内部锁，由 LocalStore 的磁盘写入边界串行调用。
 */
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

    /** 先写正文再返回可引用它的主 JSON；恢复时强制重写，以修复同名但已损坏的载荷。 */
    fun encode(state: LibraryState, forcePayloadWrite: Boolean = false): String {
        val payload = LongText(state.savedArticles, state.drafts)
        val hash = if (!forcePayloadWrite && payload == previous) requireNotNull(previousHash) else {
            val text = appJson.encodeToString(payload)
            val name = hashName(text)
            directory.mkdirs()
            // 即使哈希已存在也重新写入，使恢复能够修复先前损坏的正文载荷。
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

    /** 兼容正文直接存于旧 JSON 的格式；存在外置索引时必须通过哈希校验才能恢复正文。 */
    fun decode(text: String): LibraryState {
        val json = appJson.parseToJsonElement(text).jsonObject
        val state = appJson.decodeFromJsonElement(LibraryState.serializer(), json)
        val hash = json[PAYLOAD]?.jsonPrimitive?.content ?: return state // 仍兼容读取已有的 library.json。
        val payloadText = read(file(hash))
        require(hashName(payloadText) == hash) { "文章或草稿文件校验失败" }
        val payload = appJson.decodeFromString<LongText>(payloadText)
        previous = payload; previousHash = hash
        return state.copy(savedArticles = payload.articles, drafts = payload.drafts)
    }

    /**
     * 主状态和最后良好副本都提交后才能清理；保留当前及前一份正文以支持恢复。
     * 过早清理会让仍有效的旧状态 JSON 指向已删除的正文文件。
     */
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
