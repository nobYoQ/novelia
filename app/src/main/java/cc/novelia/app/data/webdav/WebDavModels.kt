package cc.novelia.app.data.webdav

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
enum class SyncDomain(val fileName: String, val title: String) {
    SETTINGS("settings.json", "本地设置"),
    KEYWORDS("keywords.json", "标签库"),
    FAVORITES("favorites.json", "本地收藏"),
    PROGRESS("progress.json", "阅读进度"),
    BOOKMARKS("bookmarks.json", "书签"),
    NOTES("notes.json", "笔记"),
    HISTORY("reading-history.json", "阅读历史")
}

typealias SyncProjection = Map<SyncDomain, Map<String, JsonObject>>

/** 一个因果版本。真实时间不参与冲突仲裁，防止设备时钟偏差覆盖用户修改。 */
@Serializable
data class SyncValue(
    val value: JsonElement = JsonNull,
    val deleted: Boolean = false,
    val version: Map<String, Long>,
    val writer: String,
    val counter: Long
)

/** 保存全部非支配版本；仅保留赢家会破坏后续三设备合并的结合律。 */
@Serializable
data class SyncCell(val candidates: List<SyncValue> = emptyList())

@Serializable
data class SyncRecord(
    val key: String,
    val existence: SyncCell,
    val fields: Map<String, SyncCell> = emptyMap()
)

@Serializable
data class SyncDocument(
    val schemaVersion: Int = 1,
    val datasetId: String,
    val domain: SyncDomain,
    val records: Map<String, SyncRecord> = emptyMap(),
    val context: Map<String, Long> = emptyMap()
)

data class SyncConflict(
    val domain: SyncDomain,
    val recordKey: String,
    val field: String,
    val candidates: List<SyncValue>
)

/** 与业务快照一同持久化；收到远端版本时 merge，只有用户修改才能调用 track。 */
@Serializable
data class SyncReplica(
    val deviceId: String = UUID.randomUUID().toString(),
    val clock: Long = 0,
    val documents: Map<SyncDomain, SyncDocument> = emptyMap()
) {
    fun validate() {
        require(WebDavMerge.validId(deviceId) && clock >= 0) { "本机同步设备标识或时钟无效" }
        require(documents.size <= SyncDomain.entries.size) { "本机同步数据类型无效" }
        documents.forEach { (domain, document) ->
            require(domain == document.domain && clock >= (document.context[deviceId] ?: 0)) { "本机同步类型或时钟不一致" }
            WebDavMerge.validate(document)
        }
        require(documents.values.map { it.datasetId }.distinct().size <= 1) { "本机同步数据集不一致" }
    }

    fun bind(datasetId: String): SyncReplica {
        require(WebDavMerge.validId(datasetId)) { "同步数据集标识无效" }
        return copy(documents = documents.mapValues { (_, document) -> document.copy(datasetId = datasetId) })
    }

    fun merge(document: SyncDocument): SyncReplica {
        WebDavMerge.validate(document)
        val old = documents[document.domain]
        val result = if (old == null) document else WebDavMerge.merge(old, document)
        return copy(clock = maxOf(clock, result.context[deviceId] ?: 0), documents = documents + (document.domain to result))
    }

    /** 记录字段级差异。空白新设备不会对从未观察的远端记录产生删除标记。 */
    fun track(previous: SyncProjection, current: SyncProjection, domains: Set<SyncDomain> = current.keys): SyncReplica {
        val untracked = domains.filter { it !in documents && previous[it].orEmpty().isNotEmpty() }.toSet()
        if (untracked.isNotEmpty()) {
            // 首次编辑某域时同时登记该域已有资料，之后才记录删除/修改。
            return track(emptyMap(), previous.filterKeys { it in untracked }, untracked).track(previous, current, domains)
        }
        var nextClock = maxOf(clock, documents.values.maxOfOrNull { it.context[deviceId] ?: 0 } ?: 0)
        var observed = documents.values.fold(emptyMap<String, Long>(), { vector, document -> WebDavMerge.joinVector(vector, document.context) })
        val nextDocuments = documents.toMutableMap()
        val datasetId = documents.values.firstOrNull()?.datasetId ?: "unbound"
        domains.sortedBy { it.ordinal }.forEach { domain ->
            val before = previous[domain].orEmpty()
            val after = current[domain].orEmpty()
            var document = nextDocuments[domain] ?: SyncDocument(datasetId = datasetId, domain = domain)
            val records = document.records.toMutableMap()
            (before.keys + after.keys).sorted().forEach { key ->
                val old = before[key]
                val new = after[key]
                if (old == new) return@forEach
                require(nextClock < Long.MAX_VALUE) { "同步逻辑时钟已达到上限" }
                nextClock += 1
                observed = observed + (deviceId to nextClock)
                fun changed(value: JsonElement = JsonNull, deleted: Boolean = false) = SyncCell(listOf(
                    SyncValue(value, deleted, observed, deviceId, nextClock)))
                val existing = records[key]
                if (new == null) {
                    // 本机只删除自己实际观察过的记录，不删除未知远端数据。
                    records[key] = SyncRecord(key, changed(deleted = true), existing?.fields.orEmpty())
                } else {
                    val reactivating = existing == null || WebDavMerge.winner(existing.existence, existence = true)?.deleted != false
                    val fields = existing?.fields.orEmpty().toMutableMap()
                    (old.orEmpty().keys + new.keys).sorted().forEach { field ->
                        if (old?.get(field) != new[field] || reactivating) {
                            fields[field] = new[field]?.let { changed(it) } ?: changed(deleted = true)
                        }
                    }
                    val existence = if (old == null || reactivating) changed(JsonPrimitive(true)) else existing!!.existence
                    records[key] = SyncRecord(key, existence, fields)
                }
                document = document.copy(context = WebDavMerge.joinVector(document.context, observed))
            }
            nextDocuments[domain] = document.copy(records = records.toSortedMap())
        }
        return copy(clock = nextClock, documents = nextDocuments)
    }
}
