package cc.novelia.app.data.webdav

import kotlinx.serialization.json.JsonObject

/** 条件提交循环独立于 Android；故障、配置切换及本地并发修改可以直接验证。 */
internal data class WebDavExchangeResult(val etag: String, val document: SyncDocument, val captured: SyncDocument)

/** 只预约本机写入序号；尚未成功提交的远端业务值不能提前应用。 */
internal fun reserveWebDavClock(replica: SyncReplica, document: SyncDocument): SyncReplica =
    replica.copy(clock = maxOf(replica.clock, document.context[replica.deviceId] ?: 0))

internal suspend fun exchangeWebDavDocument(
    readRemote: suspend (fresh: Boolean) -> WebDavCachedDomain?,
    captureLocal: suspend () -> SyncDocument,
    combine: (SyncDocument, SyncDocument?) -> SyncDocument,
    validate: (SyncDocument) -> Unit,
    upload: suspend (SyncDocument, String?) -> String,
    ensureCurrent: () -> Unit,
): WebDavExchangeResult {
    var remote = readRemote(false)
    var observedRemote = remote != null
    repeat(4) { attempt ->
        ensureCurrent()
        val captured = captureLocal()
        val merged = combine(captured, remote?.document)
        validate(merged)
        try {
            val etag = if(remote?.document == merged) requireNotNull(remote).etag else {
                ensureCurrent()
                upload(merged, remote?.etag)
            }
            ensureCurrent()
            return WebDavExchangeResult(etag, merged, captured)
        } catch(error: WebDavException) {
            if(error.failure != WebDavFailure.CONFLICT || attempt == 3) throw error
            remote = readRemote(true)
            if(observedRemote && remote == null) throw WebDavException(WebDavFailure.INVALID_DATA,
                "已读取的数据文件消失，请检查同步目录；本机数据已保留")
            observedRemote = observedRemote || remote != null
        }
    }
    error("远端持续变化，请稍后重试")
}

/** 首次选择远端设置后，只重放请求期间真正编辑的字段，保留同记录其他远端字段。 */
internal fun rebaseProjectionChanges(base: Map<String, JsonObject>, before: Map<String, JsonObject>, after: Map<String, JsonObject>): Map<String, JsonObject> {
    val result = base.toMutableMap()
    (before.keys + after.keys).forEach { key ->
        val old = before[key]
        val new = after[key]
        if(old == new) return@forEach
        if(new == null) result.remove(key)
        else if(old == null || key !in base) result[key] = new
        else {
            val fields = result[key].orEmpty().toMutableMap()
            (old.keys + new.keys).forEach { field ->
                if(old[field] != new[field]) new[field]?.let { fields[field] = it } ?: fields.remove(field)
            }
            result[key] = JsonObject(fields)
        }
    }
    return result
}

/** 根据真实字段版本重放用户操作，往返编辑回到原值仍然是一项新的明确选择。 */
internal fun rebaseDocumentChanges(base: Map<String, JsonObject>, before: SyncDocument, after: SyncDocument): Map<String, JsonObject> {
    require(before.domain == after.domain && before.datasetId == after.datasetId) { "同步重放的数据类型或数据集不一致" }
    val currentValues = WebDavMerge.materialize(after)
    val result = base.toMutableMap()
    (before.records.keys + after.records.keys).forEach { key ->
        val old = before.records[key]
        val current = after.records[key]
        // 单独预约时钟或更新文档上下文没有修改记录，不制造业务编辑。
        if (old == current) return@forEach
        val value = currentValues[key]
        if (old?.existence != current?.existence) {
            // 明确删除或恢复应重放整个存在性；恢复时使用完整记录。
            if (value == null) result.remove(key) else result[key] = value
        } else if (value != null && current != null) {
            if (key !in result) {
                result[key] = value
            } else {
                val fields = result.getValue(key).toMutableMap()
                (old?.fields.orEmpty().keys + current.fields.keys).forEach { field ->
                    if (old?.fields?.get(field) != current.fields[field]) {
                        value[field]?.let { fields[field] = it } ?: fields.remove(field)
                    }
                }
                result[key] = JsonObject(fields)
            }
        }
    }
    return result
}
