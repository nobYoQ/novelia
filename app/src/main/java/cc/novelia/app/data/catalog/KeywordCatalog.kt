package cc.novelia.app.data.catalog

import kotlinx.serialization.Serializable

/**
 * 以原文为唯一键的本地标签；翻译和分类仅用于展示与选择，不替换发给原站的原文。
 * translationEdited/categoryEdited 分别保护用户的翻译和分类修改，显式清空翻译也是用户选择。
 * lastUsedAt 记录用于搜索的时间，影响推荐与容量淘汰；浏览观察本身不会更新它。
 */
@Serializable
data class KeywordEntry(
    val original: String,
    val translation: String = "",
    val category: String = "其他",
    val common: Boolean = false,
    val lastUsedAt: Long = 0,
    val translationEdited: Boolean = false,
    val categoryEdited: Boolean = false,
) {
    val label: String get() = if(translation.isBlank()) original else "$original ($translation)"
}

/**
 * 标签词表的纯变换与匹配规则，初始映射来自原站 web/src/util/web/keyword.ts。
 * 原文、用户译名、内置译名和别名均可用于查找，搜索表达式始终使用 original。
 * 容量限制用于浏览时渐进收集；完整导入由 KeywordLibrary 先校验，避免静默截断。
 */
object KeywordCatalog {
    const val MAX_TEXT_LENGTH = 256
    const val MAX_ENTRIES = 20_000
    val defaultCategories = listOf("题材", "人物", "情节", "其他")
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

    /** 已有条目始终优先于默认值，包括用户显式设为空的翻译。 */
    fun withDefaults(entries: Collection<KeywordEntry>): List<KeywordEntry> =
        bounded(entries + common)

    /** 优先保留用户编辑和最近使用的词条，再为新观察到的标签腾出容量。 */
    fun bounded(entries: Collection<KeywordEntry>, limit: Int = MAX_ENTRIES): List<KeywordEntry> {
        require(limit > 0)
        val valid = entries.filter { it.original.isNotBlank() && it.original.length <= MAX_TEXT_LENGTH && it.translation.length <= MAX_TEXT_LENGTH }
            .distinctBy { it.original }
        if(valid.size <= limit) return valid
        return valid.withIndex().sortedWith(compareByDescending<IndexedValue<KeywordEntry>> { it.value.translationEdited || it.value.categoryEdited }
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

    /** 翻译和分类分别按编辑标记合并，最近使用时间取较新值；同原文仍只保留一个词条。 */
    fun merge(current: List<KeywordEntry>, incoming: Collection<KeywordEntry>, addDefaults: Boolean = true): List<KeywordEntry> {
        val imported = incoming.filter { it.original.isNotBlank() }.associateBy { it.original }
        val merged = current.map { entry ->
            val restored = imported[entry.original]
            val translated = if(restored != null && !entry.translationEdited) entry.copy(
                translation = restored.translation, translationEdited = restored.translationEdited,
            ) else entry
            translated.copy(lastUsedAt = maxOf(entry.lastUsedAt, restored?.lastUsedAt ?: 0),
                category = if(restored != null && !entry.categoryEdited) restored.category else entry.category,
                categoryEdited = entry.categoryEdited || restored?.categoryEdited == true)
        }
        return if(addDefaults) withDefaults(merged + incoming) else bounded(merged + incoming)
    }

    /** 完全匹配优先，其次是前缀和包含匹配；同分时优先最近使用及常用词条。 */
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

    /** 上游解析器只移除末尾一个 '$'，原词内部或末尾已有的 '$' 可被保留。 */
    fun canSearch(original: String): Boolean = original.isNotBlank() && original.length <= MAX_TEXT_LENGTH && original.none(Char::isWhitespace) &&
        !original.startsWith('-')
}
