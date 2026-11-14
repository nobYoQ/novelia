package cc.novelia.app.data.webdav

import cc.novelia.app.data.model.ReadingStatusControl
import cc.novelia.app.data.model.SavedBook
import kotlinx.serialization.json.*

/** status 的 v2 原子组；v1 文本始终视为来源不明的手动状态。 */
internal object WebDavReadingStatus {
    private val json = Json { encodeDefaults = true; explicitNulls = true }
    fun encode(status: String, control: ReadingStatusControl): JsonObject =
        JsonObject(json.encodeToJsonElement(ReadingStatusControl.serializer(), control).jsonObject + ("status" to JsonPrimitive(status)))
    fun encode(saved: SavedBook): JsonElement = saved.readingStatusControl?.let { encode(saved.status, it) } ?: JsonPrimitive(saved.status)

    fun status(value: JsonElement): String = if(value is JsonObject) value.getValue("status").jsonPrimitive.content else value.jsonPrimitive.content
    fun control(value: JsonElement): ReadingStatusControl? = (value as? JsonObject)?.let {
        json.decodeFromJsonElement(ReadingStatusControl.serializer(), JsonObject(it - "status"))
    }
    fun revision(value: JsonElement): String = control(value)?.revision ?: "legacy"
    fun automatic(value: JsonElement): Boolean = control(value)?.automatic == true

    fun validate(value: JsonElement) {
        if(value is JsonPrimitive) {
            require(value.isString && value.content in setOf("想读", "在读", "读完")) { "阅读状态无效" }
            return
        }
        require(value is JsonObject && value.keys == setOf("status", "automatic", "revision", "supersedes", "knownChapterCount", "completedChapterCount")) { "阅读状态管理组不完整" }
        require(value.getValue("status") is JsonPrimitive && value.getValue("status").jsonPrimitive.isString && status(value) in setOf("想读", "在读", "读完")) { "阅读状态无效" }
        val control = requireNotNull(control(value))
        require(control.valid && (!control.automatic || status(value) != "读完" || control.completedChapterCount != null)) { "阅读状态管理版本无效" }
    }

    /** 自动事件只能取代同一管理版本，或用户明确恢复自动时已观察并取代的版本。 */
    fun canReplace(new: SyncValue, old: SyncValue): Boolean = !automatic(new.value) ||
        revision(new.value) == revision(old.value) || revision(old.value) in control(new.value)!!.supersedes

    fun supersedes(new: SyncValue, old: SyncValue): Boolean = revision(old.value) != "legacy" &&
        revision(old.value) in control(new.value)?.supersedes.orEmpty()

    fun winner(cell: SyncCell): SyncValue? {
        val manual = cell.candidates.filter { !it.deleted && !automatic(it.value) }
        if(manual.isNotEmpty()) return WebDavMerge.winner(SyncCell(manual))
        // 同一自动版本并发读到不同位置时，不因回看或更大的设备计数撤销完成。
        fun rank(value: SyncValue) = if(value.deleted) -1 to -1 else (control(value.value)?.knownChapterCount ?: 0) to
            listOf("想读", "在读", "读完").indexOf(status(value.value))
        val best = cell.candidates.maxWithOrNull(compareBy<SyncValue>({ rank(it).first }, { rank(it).second })) ?: return null
        return WebDavMerge.winner(SyncCell(cell.candidates.filter { rank(it) == rank(best) }))
    }
}
