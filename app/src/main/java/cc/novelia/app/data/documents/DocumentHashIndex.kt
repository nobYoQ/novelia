package cc.novelia.app.data.documents


/**
 * “文档 ID → 源文件哈希”的小型持久索引，用于重复导入检测，避免每次扫描整本旧文档。
 * find 只在调用方提供的文档集合内查找，缺失哈希时惰性补齐，批量更新后保存一次。
 * 空哈希无法可靠去重；索引自身不加锁，由 LocalStore 的文档锁保护。
 */
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
