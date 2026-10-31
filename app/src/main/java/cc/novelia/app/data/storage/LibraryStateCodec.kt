package cc.novelia.app.data.storage

import cc.novelia.app.data.model.*
import cc.novelia.app.data.webdav.SyncReplica
import java.io.File
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*

/**
 * 主文件只提交各分区的内容哈希。先写不可变载荷，再由 LocalStore 原子提交主索引；
 * 翻页只编码阅读分区，书架、笔记、设置和同步资料未变时复用原文件。
 * 仍读取旧内联状态及外置草稿格式；导出备份继续使用完整 LibraryState。
 * 缓存和清理由 LocalStore 的磁盘锁串行保护。
 */
internal class LibraryStateCodec(
    private val directory: File,
    private val read: (File) -> String,
    private val write: (File, String) -> Unit
) {
    @Serializable private data class LongText(val drafts: Map<String, String>)
    @Serializable private data class Shelf(val books: List<SavedBook>, val folders: List<String>, val folderIds: Map<String, String>)
    @Serializable private data class Reading(val positions: Map<String, Position>,
        val readingHistory: Map<String, ReadingHistoryEntry>, val historyMigrated: Boolean,
        val readingStatistics: ReadingStatistics = ReadingStatistics())

    private inner class Part<T>(private val serializer: KSerializer<T>) {
        private var previous: T? = null
        private var previousHash: String? = null
        fun encode(value: T, force: Boolean): String {
            if(!force && previousHash != null && value == previous) return previousHash!!
            val text = appJson.encodeToString(serializer, value)
            val hash = hashName(text)
            directory.mkdirs()
            // 强制恢复也修复同名缺失或损坏文件；缓存只在写入成功后前移。
            write(file(hash), text)
            accept(value, hash)
            return hash
        }
        fun decode(hash: String): T {
            val text = read(file(hash))
            require(hashName(text) == hash) { "书库分区校验失败" }
            return appJson.decodeFromString(serializer, text)
        }
        fun accept(value: T, hash: String) { previous = value; previousHash = hash }
    }

    private val shelf = Part(Shelf.serializer())
    private val reading = Part(Reading.serializer())
    private val notes = Part(ListSerializer(Note.serializer()))
    private val sync = Part(SyncReplica.serializer())
    private val core = Part(LibraryState.serializer())
    private val drafts = Part(LongText.serializer())
    private var committedHashes = emptySet<String>()
    private var previousCommittedHashes = emptySet<String>()
    private var canCompact = false

    fun encode(state: LibraryState, forcePayloadWrite: Boolean = false): String {
        val parts = linkedMapOf(
            "shelf" to shelf.encode(Shelf(state.books, state.folders, state.folderIds), forcePayloadWrite),
            "reading" to reading.encode(Reading(state.positions, state.readingHistory, state.historyMigrated, state.readingStatistics), forcePayloadWrite),
            "notes" to notes.encode(state.notes, forcePayloadWrite),
            "sync" to sync.encode(state.syncReplica, forcePayloadWrite),
            "core" to core.encode(coreState(state), forcePayloadWrite),
            "drafts" to drafts.encode(LongText(state.drafts), forcePayloadWrite)
        )
        return buildJsonObject {
            put(FORMAT, 2)
            put(PARTS, JsonObject(parts.mapValues { JsonPrimitive(it.value) }))
        }.toString()
    }

    fun decode(text: String): LibraryState {
        val json = appJson.parseToJsonElement(text).jsonObject
        if(FORMAT !in json) {
            require(PARTS !in json) { "书库分区版本缺失" }
            return decodeLegacy(json)
        }
        require(json.getValue(FORMAT).jsonPrimitive.int == 2) { "不支持的书库存储版本" }
        val parts = json.getValue(PARTS).jsonObject
        require(parts.keys == PART_NAMES) { "书库分区索引不完整" }
        fun hash(name: String) = parts.getValue(name).jsonPrimitive.content
        val s = shelf.decode(hash("shelf"))
        val r = reading.decode(hash("reading"))
        val n = notes.decode(hash("notes"))
        val replica = sync.decode(hash("sync"))
        val c = core.decode(hash("core"))
        val d = drafts.decode(hash("drafts"))
        val state = c.copy(books = s.books, folders = s.folders, folderIds = s.folderIds,
            positions = r.positions, readingHistory = r.readingHistory, historyMigrated = r.historyMigrated, readingStatistics = r.readingStatistics,
            notes = n, syncReplica = replica, drafts = d.drafts)
            .let { it.copy(edgeToolbar = it.edgeToolbar.normalized()) }
        state.syncReplica.validate()
        state.readingStatistics.requireValid()
        // 整份快照校验成功后才更新缓存，失败主文件不能污染备用副本的加载。
        shelf.accept(s, hash("shelf")); reading.accept(r, hash("reading")); notes.accept(n, hash("notes"))
        sync.accept(replica, hash("sync")); core.accept(c, hash("core")); drafts.accept(d, hash("drafts"))
        committedHashes = parts.values.map { it.jsonPrimitive.content }.toSet()
        return state
    }

    private fun decodeLegacy(json: JsonObject): LibraryState {
        val state = appJson.decodeFromJsonElement(LibraryState.serializer(), json)
            .let { it.copy(edgeToolbar = it.edgeToolbar.normalized()) }.also { it.syncReplica.validate() }
        state.readingStatistics.requireValid()
        val hash = json[LEGACY_PAYLOAD]?.jsonPrimitive?.content
        val result = if(hash == null) state else state.copy(drafts = drafts.decode(hash).drafts)
        // 旧草稿可能夹带已废弃文章字段；下次保存重编码，保留旧文件供回退。
        committedHashes = setOfNotNull(hash)
        return result
    }

    /** 只有主索引真正完成原子提交后调用；编码尝试不算已提交快照。 */
    fun committed(text: String) {
        val hashes = appJson.parseToJsonElement(text).jsonObject.getValue(PARTS).jsonObject
            .values.map { it.jsonPrimitive.content }.toSet()
        if(hashes != committedHashes) {
            previousCommittedHashes = committedHashes
            committedHashes = hashes
        }
        canCompact = true
    }

    /** 主索引及最后良好副本都写入成功后调用，保留最近两次成功提交所引用的载荷。 */
    fun compact() {
        if(!canCompact) return
        val keep = committedHashes + previousCommittedHashes
        directory.listFiles()?.filter { it.isFile && it.extension == "json" &&
            HASH.matches(it.nameWithoutExtension) && it.nameWithoutExtension !in keep }?.forEach { it.delete() }
        canCompact = false
    }

    private fun file(hash: String): File {
        require(HASH.matches(hash)) { "书库分区索引无效" }
        return File(directory, "$hash.json")
    }

    private fun coreState(state: LibraryState) = state.copy(books = emptyList(), folders = emptyList(), folderIds = emptyMap(),
        positions = emptyMap(), readingHistory = emptyMap(), historyMigrated = false, readingStatistics = ReadingStatistics(), notes = emptyList(),
        drafts = emptyMap(), syncReplica = EMPTY_REPLICA)

    private companion object {
        const val FORMAT = "localLibraryFormat"
        const val PARTS = "localLibraryParts"
        const val LEGACY_PAYLOAD = "localLongTextPayload"
        val PART_NAMES = setOf("shelf", "reading", "notes", "sync", "core", "drafts")
        val HASH = Regex("[a-f0-9]{64}")
        val EMPTY_REPLICA = SyncReplica(deviceId = "00000000-0000-0000-0000-000000000000")
    }
}
