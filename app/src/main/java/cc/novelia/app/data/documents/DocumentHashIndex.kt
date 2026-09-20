package cc.novelia.app.data.documents


/** A small persisted index: legacy documents are inspected only once, not once per import. */
internal class DocumentHashIndex(
    initial: Map<String, String> = emptyMap(),
    private val readHash: (String) -> String,
    private val persist: (Map<String, String>) -> Unit,
) {
    private val hashes = initial.toMutableMap()

    fun find(hash: String, documentIds: List<String>, checkCancelled: () -> Unit = {}): String? {
        if (hash.isBlank()) return null
        var changed = false
        var match: String? = null
        for (id in documentIds) {
            checkCancelled()
            val known = hashes[id] ?: runCatching { readHash(id) }.getOrNull()?.also {
                hashes[id] = it
                changed = true
            }
            if (known == hash && match == null) match = id
        }
        if (changed) persist(hashes.toMap())
        return match
    }

    fun record(id: String, hash: String) {
        if (hashes[id] == hash) return
        hashes[id] = hash
        persist(hashes.toMap())
    }

    fun remove(id: String) {
        if (hashes.remove(id) != null) persist(hashes.toMap())
    }
}
