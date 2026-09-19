package cc.novelia.app.data

import kotlinx.serialization.Serializable

@Serializable
data class KeywordEntry(
    val original: String,
    val translation: String = "",
    val category: String = "其他",
    val common: Boolean = false,
    val lastUsedAt: Long = 0,
    val translationEdited: Boolean = false,
) {
    val label: String get() = if(translation.isBlank()) original else "$original ($translation)"
}

/** The initial vocabulary comes from the site's web/src/util/web/keyword.ts mapping. */
object KeywordCatalog {
    const val MAX_TEXT_LENGTH = 256
    const val MAX_ENTRIES = 20_000
    val categories = listOf("全部", "题材", "人物", "情节", "其他")
    val common = listOf(
        KeywordEntry("ファンタジー", "奇幻", "题材", true),
        KeywordEntry("ラブコメ", "爱情喜剧", "题材", true),
        KeywordEntry("スクールラブ", "校园爱情", "题材", true),
        KeywordEntry("ミステリー", "推理", "题材", true),
        KeywordEntry("コメディ", "喜剧", "题材", true),
        KeywordEntry("ホームドラマ", "家庭剧", "题材", true),
        KeywordEntry("ゲーム", "游戏", "题材", true),
        KeywordEntry("ヤンデレ", "病娇", "人物", true),
        KeywordEntry("ツンデレ", "傲娇", "人物", true),
        KeywordEntry("ヒーロー", "主角", "人物", true),
        KeywordEntry("ヒロイン", "女主角", "人物", true),
        KeywordEntry("アイドル", "偶像", "人物", true),
        KeywordEntry("ハーレム", "后宫", "情节", true),
        KeywordEntry("バトル", "战斗", "情节", true),
        KeywordEntry("ほのぼの", "温暖", "情节", true),
        KeywordEntry("ハッピーエンド", "圆满结局", "情节", true),
        KeywordEntry("バッドエンド", "悲剧结局", "情节", true),
        KeywordEntry("嘘コク", "假告白", "情节", true),
        KeywordEntry("ダンジョン", "迷宫", "情节", true),
        KeywordEntry("チート", "作弊", "情节", true),
    )
    private val aliases = mapOf(
        "ほのぼの" to listOf("日常", "温馨", "治愈"),
        "ハッピーエンド" to listOf("HappyEnd", "HE", "好结局"),
        "バッドエンド" to listOf("BadEnd", "BE", "坏结局"),
        "チート" to listOf("开挂"),
        "ラブコメ" to listOf("恋爱喜剧"),
    )

    /** An existing entry, including an explicitly empty translation, always wins over defaults. */
    fun withDefaults(entries: Collection<KeywordEntry>): List<KeywordEntry> =
        bounded(entries + common)

    /** Keep reader edits and recently used vocabulary, then make room for newly observed tags. */
    fun bounded(entries: Collection<KeywordEntry>, limit: Int = MAX_ENTRIES): List<KeywordEntry> {
        require(limit > 0)
        val valid = entries.filter { it.original.isNotBlank() && it.original.length <= MAX_TEXT_LENGTH && it.translation.length <= MAX_TEXT_LENGTH }
            .distinctBy { it.original }
        if(valid.size <= limit) return valid
        return valid.withIndex().sortedWith(compareByDescending<IndexedValue<KeywordEntry>> { it.value.translationEdited }
            .thenByDescending { it.value.lastUsedAt }.thenByDescending { it.value.common }.thenByDescending { it.index })
            .take(limit).sortedBy { it.index }.map { it.value }
    }

    fun observe(entries: List<KeywordEntry>, originals: Collection<String>): List<KeywordEntry> {
        val known = entries.mapTo(mutableSetOf()) { it.original }
        val defaults = common.associateBy { it.original }
        val added = originals.map(String::trim).filter { it.isNotBlank() && it.length <= MAX_TEXT_LENGTH && known.add(it) }
            .map { defaults[it] ?: KeywordEntry(it) }
        return if(added.isEmpty()) entries else bounded(entries + added)
    }

    fun markUsed(entries: List<KeywordEntry>, originals: Collection<String>, now: Long): List<KeywordEntry> {
        val selected = originals.map(String::trim).filter { it.isNotBlank() && it.length <= MAX_TEXT_LENGTH }.toSet()
        if(selected.isEmpty()) return entries
        val lookup = (common + entries).associateBy { it.original }
        val used = selected.map { (lookup[it] ?: KeywordEntry(it)).copy(lastUsedAt = now) }
        return bounded(entries.filterNot { it.original in selected } + used)
    }

    fun translate(entries: List<KeywordEntry>, original: String, translation: String): List<KeywordEntry> {
        require(original.isNotBlank() && original.length <= MAX_TEXT_LENGTH && translation.length <= MAX_TEXT_LENGTH) { "标签原文和翻译最多 $MAX_TEXT_LENGTH 字符" }
        val existing = entries.firstOrNull { it.original == original } ?: common.firstOrNull { it.original == original } ?: KeywordEntry(original)
        return bounded(entries.filterNot { it.original == original } + existing.copy(translation = translation, translationEdited = true))
    }

    fun merge(current: List<KeywordEntry>, incoming: Collection<KeywordEntry>): List<KeywordEntry> {
        val imported = incoming.filter { it.original.isNotBlank() }.associateBy { it.original }
        val merged = current.map { entry ->
            val restored = imported[entry.original]
            val translated = if(restored != null && !entry.translationEdited) entry.copy(
                translation = restored.translation, translationEdited = restored.translationEdited,
            ) else entry
            translated.copy(lastUsedAt = maxOf(entry.lastUsedAt, restored?.lastUsedAt ?: 0))
        }
        return withDefaults(merged + incoming)
    }

    fun suggestions(entries: List<KeywordEntry>, query: String, category: String = "全部", limit: Int = 20): List<KeywordEntry> {
        val normalized = query.trim().lowercase()
        fun score(entry: KeywordEntry): Int {
            if(normalized.isEmpty()) return 0
            val names = listOf(entry.original, entry.translation) +
                listOfNotNull(common.firstOrNull { it.original == entry.original }?.translation) + aliases[entry.original].orEmpty()
            return names.filter(String::isNotBlank).minOfOrNull { name ->
                val value = name.lowercase()
                when { value == normalized -> 0; value.startsWith(normalized) -> 1; normalized in value -> 2; else -> 3 }
            } ?: 3
        }
        return entries.asSequence().filter { category == "全部" || it.category == category }
            .map { it to score(it) }.filter { it.second < 3 }
            .sortedWith(compareBy<Pair<KeywordEntry, Int>> { it.second }
                .thenByDescending { it.first.lastUsedAt }.thenByDescending { it.first.common }.thenBy { it.first.original })
            .take(limit).map { it.first }.toList()
    }

    fun exactMatch(entries: List<KeywordEntry>, query: String): KeywordEntry? {
        val text = query.trim()
        return suggestions(entries, text).firstOrNull { entry ->
            (listOf(entry.original, entry.translation) + listOfNotNull(common.firstOrNull { it.original == entry.original }?.translation) +
                aliases[entry.original].orEmpty()).any { it.equals(text, ignoreCase = true) }
        }
    }

    /** The upstream parser removes one suffix '$'; internal/trailing '$' in the original is safe. */
    fun canSearch(original: String): Boolean = original.isNotBlank() && original.length <= MAX_TEXT_LENGTH && original.none(Char::isWhitespace) &&
        !original.startsWith('-')
}
