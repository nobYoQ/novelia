package cc.novelia.app.data.sync

import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.PendingAction

/** 按完整资源路径段匹配，避免相似书名或其他用户操作相互冲突。 */
internal fun pendingBookKey(action: PendingAction): String? {
    val segments = action.path.trim('/').split('/')
    if (segments.firstOrNull() != "user") return null
    return when (segments.getOrNull(1)) {
        "read-history" -> if (segments.size == 4) segments.drop(2).joinToString("/") else null
        "favored-web" -> if (segments.size == 5) segments.drop(3).joinToString("/") else null
        "favored-wenku" -> if (segments.size == 4) "wenku/${segments[3]}" else null
        else -> null
    }
}

internal fun LibraryState.updateCloudPending(transform: (List<PendingAction>) -> List<PendingAction>): LibraryState {
    val updated = transform(pending)
    val ids = updated.groupBy { it.account }.mapValues { (_, actions) -> actions.map { it.id }.toSet() }
    return copy(pending = updated, syncStatus = syncStatus.mapValues { (account, status) ->
        val remaining = ids[account].orEmpty()
        status.copy(failures = status.failures.filterKeys { it in remaining }, blockedActions = status.blockedActions.intersect(remaining))
    })
}
