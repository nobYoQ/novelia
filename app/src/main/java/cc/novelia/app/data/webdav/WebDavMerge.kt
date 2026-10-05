package cc.novelia.app.data.webdav

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 不访问网络或本地存储的因果合并。删除标记不按时间过期。 */
object WebDavMerge {
    const val EXISTENCE = "__existence"
    const val MAX_RECORDS = 100_000
    const val MAX_DEVICES = 256
    const val MAX_CANDIDATES = 64
    private val idPattern = Regex("[A-Za-z0-9._-]{1,128}")

    fun validId(value: String): Boolean = idPattern.matches(value)

    fun joinVector(first: Map<String, Long>, second: Map<String, Long>): Map<String, Long> =
        (first.keys + second.keys).sorted().associateWith { maxOf(first[it] ?: 0, second[it] ?: 0) }

    fun dominates(first: Map<String, Long>, second: Map<String, Long>): Boolean =
        (first.keys + second.keys).all { (first[it] ?: 0) >= (second[it] ?: 0) } &&
            (first.keys + second.keys).any { (first[it] ?: 0) > (second[it] ?: 0) }

    private fun canonical(value: JsonElement): String = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { (_, item) -> canonicalElement(item) }).toString()
        else -> canonicalElement(value).toString()
    }

    private fun canonicalElement(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { (_, item) -> canonicalElement(item) })
        is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(value.map(::canonicalElement))
        else -> value
    }

    private val candidateOrder = compareBy<SyncValue>({ it.counter }, { it.writer }, { it.deleted }, { canonical(it.value) },
        { it.version.toSortedMap().entries.joinToString { entry -> "${entry.key}:${entry.value}" } })

    private fun mergeCell(first: SyncCell, second: SyncCell): SyncCell {
        val all = (first.candidates + second.candidates).distinct()
        val maximal = all.filter { candidate -> all.none { other -> dominates(other.version, candidate.version) } }
        require(maximal.size <= MAX_CANDIDATES) { "同时修改版本过多，请先解决同步冲突" }
        return SyncCell(maximal.sortedWith(candidateOrder))
    }

    fun winner(cell: SyncCell, existence: Boolean = false): SyncValue? {
        val candidates = if (existence && cell.candidates.any { it.deleted }) cell.candidates.filter { it.deleted } else cell.candidates
        return candidates.maxWithOrNull(candidateOrder)
    }

    fun merge(first: SyncDocument, second: SyncDocument): SyncDocument {
        validate(first)
        validate(second)
        require(first.datasetId == second.datasetId && first.domain == second.domain) { "同步数据集或数据类型不一致" }
        val records = (first.records.keys + second.records.keys).sorted().associateWith { key ->
            val a = first.records[key]
            val b = second.records[key]
            when {
                a == null -> b!!
                b == null -> a
                else -> SyncRecord(key, mergeCell(a.existence, b.existence),
                    (a.fields.keys + b.fields.keys).sorted().associateWith { field ->
                        mergeCell(a.fields[field] ?: SyncCell(), b.fields[field] ?: SyncCell())
                    })
            }
        }
        return first.copy(records = records, context = joinVector(first.context, second.context)).also(::validate)
    }

    /** 新设备默认词表不能撤销已有用户译名或分类（包括用户显式清空译名）。
     * 选择写成真实本设备因果版本，因此再次和初始化前的本地快照合并也不会复活默认值。
     */
    fun bootstrapKeywords(local: SyncDocument, remote: SyncDocument, deviceId: String): SyncDocument {
        require(local.domain == SyncDomain.KEYWORDS && remote.domain == SyncDomain.KEYWORDS && validId(deviceId)) { "标签库初始化类型或设备标识无效" }
        var merged = merge(local, remote)
        val localValues = materialize(local)
        val remoteValues = materialize(remote)
        (localValues.keys intersect remoteValues.keys).sorted().forEach { key ->
            if (!key.startsWith("@entry/")) return@forEach
            val a = localValues.getValue(key)
            val b = remoteValues.getValue(key)
            listOf("translation" to "translationEdited", "categoryId" to "categoryEdited").forEach { (field, editedField) ->
                val localEdited = a[editedField] == JsonPrimitive(true)
                val remoteEdited = b[editedField] == JsonPrimitive(true)
                if (localEdited == remoteEdited) return@forEach
                val selected = if (remoteEdited) b else a
                val counter = (merged.context[deviceId] ?: 0).also { require(it < Long.MAX_VALUE) { "同步逻辑时钟已达到上限" } } + 1
                val version = merged.context + (deviceId to counter)
                val record = merged.records.getValue(key)
                val fields = record.fields + mapOf(field to SyncCell(listOf(SyncValue(selected.getValue(field), version = version, writer = deviceId, counter = counter))),
                    editedField to SyncCell(listOf(SyncValue(JsonPrimitive(true), version = version, writer = deviceId, counter = counter))))
                merged = merged.copy(records = merged.records + (key to record.copy(fields = fields)), context = version)
            }
        }
        return merged.also(::validate)
    }

    /** 首次加入时明确选择设置来源；屏蔽集合始终逐成员合并，不受默认偏好选择影响。 */
    fun bootstrapSettings(local: SyncDocument, remote: SyncDocument, deviceId: String, preferLocal: Boolean): SyncDocument {
        require(local.domain == SyncDomain.SETTINGS && remote.domain == SyncDomain.SETTINGS && validId(deviceId)) { "设置初始化类型或设备标识无效" }
        var merged = merge(local, remote)
        val preferred = if (preferLocal) local else remote
        preferred.records.toSortedMap().filterKeys { !it.startsWith("@blocked/") }.forEach { (key, record) ->
            val existence = requireNotNull(winner(record.existence, existence = true))
            val counter = (merged.context[deviceId] ?: 0).also { require(it < Long.MAX_VALUE) { "同步逻辑时钟已达到上限" } } + 1
            val version = merged.context + (deviceId to counter)
            val chosenExistence = SyncCell(listOf(existence.copy(version = version, writer = deviceId, counter = counter)))
            val oldFields = merged.records.getValue(key).fields
            val fields = if (existence.deleted) oldFields else oldFields + record.fields.mapValues { (_, cell) ->
                val chosen = requireNotNull(winner(cell))
                SyncCell(listOf(chosen.copy(version = version, writer = deviceId, counter = counter)))
            }
            merged = merged.copy(records = merged.records + (key to record.copy(existence = chosenExistence, fields = fields)), context = version)
        }
        return merged.also(::validate)
    }

    fun materialize(document: SyncDocument): Map<String, JsonObject> = document.records.toSortedMap().mapNotNull { (key, record) ->
        if (winner(record.existence, existence = true)?.deleted != false) return@mapNotNull null
        key to JsonObject(record.fields.toSortedMap().mapNotNull { (field, cell) ->
            winner(cell)?.takeUnless { it.deleted }?.let { field to it.value }
        }.toMap())
    }.toMap()

    fun conflicts(document: SyncDocument): List<SyncConflict> = document.records.toSortedMap().flatMap { (key, record) ->
        if (winner(record.existence, existence = true)?.deleted != false) return@flatMap emptyList()
        // 普通字段仍保留候选，以供诊断；需要用户选择的两类数据明确暴露。
        record.fields.toSortedMap().mapNotNull { (field, cell) ->
            if ((document.domain == SyncDomain.NOTES && field == "text" ||
                    document.domain == SyncDomain.PROGRESS && field == "position") &&
                cell.candidates.map { it.deleted to it.value }.distinct().size > 1) SyncConflict(document.domain, key, field, cell.candidates) else null
        }
    }

    /** 显式选择产生一个观察了所有现存版本的新版本，之后旧设备不会撤销选择。 */
    fun resolve(document: SyncDocument, recordKey: String, field: String, candidateIndex: Int, deviceId: String, counter: Long): SyncDocument {
        validate(document)
        require(validId(deviceId)) { "设备标识无效" }
        val record = requireNotNull(document.records[recordKey]) { "冲突记录已不存在" }
        val cell = if (field == EXISTENCE) record.existence else requireNotNull(record.fields[field]) { "冲突字段已不存在" }
        val selected = cell.candidates.getOrNull(candidateIndex) ?: error("冲突候选已变化")
        val observed = cell.candidates.fold(document.context) { vector, value -> joinVector(vector, value.version) }
        require(counter > (observed[deviceId] ?: 0) && counter > 0) { "冲突处理逻辑时钟无效" }
        val version = observed + (deviceId to counter)
        val resolved = SyncCell(listOf(selected.copy(version = version, writer = deviceId, counter = counter)))
        val updated = if (field == EXISTENCE) record.copy(existence = resolved) else record.copy(fields = record.fields + (field to resolved))
        return document.copy(records = document.records + (recordKey to updated), context = joinVector(document.context, version))
    }

    fun validate(document: SyncDocument, expectedDatasetId: String? = null) {
        require(document.schemaVersion == 1) { "不支持的同步协议版本" }
        require(validId(document.datasetId) && (expectedDatasetId == null || document.datasetId == expectedDatasetId)) { "同步数据集标识不一致" }
        require(document.records.size <= MAX_RECORDS && document.context.size <= MAX_DEVICES) { "同步文件记录或设备数量超过限制" }
        fun vector(value: Map<String, Long>) {
            require(value.isNotEmpty() && value.size <= MAX_DEVICES && value.all { validId(it.key) && it.value > 0 }) { "同步逻辑版本无效" }
        }
        if (document.context.isNotEmpty()) vector(document.context)
        document.records.forEach { (key, record) ->
            require(key == record.key && key.isNotBlank() && key.length <= 1024 && key.none(Char::isISOControl) && !key.startsWith("local/")) { "同步记录标识无效" }
            WebDavProjection.validateKey(document.domain, key)
            require(record.fields.size <= 100) { "同步字段过多" }
            fun cell(value: SyncCell, existence: Boolean, field: String) {
                require(value.candidates.size in 1..MAX_CANDIDATES) { "同步字段版本数量无效" }
                value.candidates.forEach { candidate ->
                    vector(candidate.version)
                    require(validId(candidate.writer) && candidate.counter > 0 && candidate.version[candidate.writer] == candidate.counter) { "同步写入版本无效" }
                    require(candidate.version.all { (device, tick) -> (document.context[device] ?: 0) >= tick }) { "同步因果上下文缺失" }
                    require(!candidate.deleted || candidate.value == JsonNull) { "删除标记不能附带数据" }
                    if (existence) require(candidate.deleted || candidate.value == JsonPrimitive(true)) { "同步记录存在性无效" }
                    else if (!candidate.deleted) WebDavProjection.validateField(document.domain, key, field, candidate.value)
                }
            }
            cell(record.existence, true, EXISTENCE)
            record.fields.forEach { (field, value) ->
                require(field.length in 1..100 && field.none(Char::isISOControl)) { "同步字段标识无效" }
                WebDavProjection.validateFieldName(document.domain, key, field)
                cell(value, false, field)
            }
        }
        WebDavProjection.validateRecords(document)
    }
}
