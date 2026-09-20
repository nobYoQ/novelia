package cc.novelia.app.data

/** Match complete resource segments; similarly named books and other user operations cannot collide. */
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
