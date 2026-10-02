package cc.novelia.app.data.catalog

import kotlinx.serialization.Serializable

/**
 * 以原文为唯一键的本地标签；翻译和分类仅用于展示与选择，不替换发给原站的原文。
 * translationEdited/categoryEdited 分别保护用户的翻译和分类修改，显式清空翻译也是用户选择。
 * lastUsedAt 记录用于搜索的时间，影响推荐排序；浏览观察本身不会更新它。
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
    val displayTranslation: String get() = KeywordCatalog.displayTranslation(this)
    val label: String get() = displayTranslation.let { if(it.isBlank() || it == original) original else "$original ($it)" }
}

/**
 * 标签词表的纯变换与匹配规则，初始映射来自原站 web/src/util/web/keyword.ts。
 * 原文、用户译名、内置译名和别名均可用于查找，搜索表达式始终使用 original。
 * 数量默认不限；用户设置上限后停止收集新标签，完整导入由 KeywordLibrary 校验，已有标签不被淘汰。
 */
object KeywordCatalog {
    const val MAX_TEXT_LENGTH = 256
    val defaultCategories = listOf("题材", "人物", "情节", "其他") + keywordCategorySeeds.map { it.name }
    // 保留旧默认值用于升级识别，不把已有的自定义译名或分类当作默认值覆盖。
    private val previousCommon = listOf(
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
        KeywordEntry("異世界", "异世界", "题材", true),
        KeywordEntry("異世界転生", "异世界转生", "情节", true),
        KeywordEntry("異世界転移", "异世界穿越", "情节", true),
        KeywordEntry("現代ファンタジー", "现代奇幻", "题材", true),
        KeywordEntry("ハイファンタジー", "架空世界奇幻", "题材", true),
        KeywordEntry("ローファンタジー", "现实世界奇幻", "题材", true),
        KeywordEntry("ダークファンタジー", "黑暗奇幻", "题材", true),
        KeywordEntry("恋愛", "恋爱", "题材", true),
        KeywordEntry("青春", "青春", "题材", true),
        KeywordEntry("学園", "校园", "题材", true),
        KeywordEntry("日常", "日常", "题材", true),
        KeywordEntry("冒険", "冒险", "情节", true),
        KeywordEntry("魔法", "魔法", "题材", true),
        KeywordEntry("剣と魔法", "剑与魔法", "题材", true),
        KeywordEntry("SF", "科幻", "题材", true),
        KeywordEntry("ホラー", "恐怖", "题材", true),
        KeywordEntry("サスペンス", "悬疑", "题材", true),
        KeywordEntry("推理", "推理", "题材", true),
        KeywordEntry("歴史", "历史", "题材", true),
        KeywordEntry("戦記", "战争故事", "题材", true),
        KeywordEntry("VRMMO", "虚拟现实网游", "题材", true),
        KeywordEntry("男主人公", "男主角", "人物", true),
        KeywordEntry("女主人公", "女主角", "人物", true),
        KeywordEntry("主人公最強", "最强主角", "人物", true),
        KeywordEntry("悪役令嬢", "反派大小姐", "人物", true),
        KeywordEntry("幼馴染", "青梅竹马", "人物", true),
        KeywordEntry("幼なじみ", "青梅竹马", "人物", true),
        KeywordEntry("聖女", "圣女", "人物", true),
        KeywordEntry("勇者", "勇者", "人物", true),
        KeywordEntry("魔王", "魔王", "人物", true),
        KeywordEntry("人外", "非人类", "人物", true),
        KeywordEntry("獣人", "兽人", "人物", true),
        KeywordEntry("溺愛", "溺爱", "情节", true),
        KeywordEntry("純愛", "纯爱", "情节", true),
        KeywordEntry("ざまぁ", "打脸／恶有恶报", "情节", true),
        KeywordEntry("追放", "放逐", "情节", true),
        KeywordEntry("婚約破棄", "解除婚约", "情节", true),
        KeywordEntry("復讐", "复仇", "情节", true),
        KeywordEntry("成り上がり", "逆袭崛起", "情节", true),
        KeywordEntry("勘違い", "误会", "情节", true),
        KeywordEntry("すれ違い", "彼此错过", "情节", true),
        KeywordEntry("スローライフ", "悠闲生活", "情节", true),
        KeywordEntry("もふもふ", "毛茸茸", "题材", true),
        KeywordEntry("シリアス", "严肃剧情", "情节", true),
        KeywordEntry("ギャグ", "搞笑", "题材", true),
        KeywordEntry("残酷な描写あり", "含残酷描写", "其他", true),
        KeywordEntry("暴力描写あり", "含暴力描写", "其他", true),
        KeywordEntry("性描写あり", "含性描写", "其他", true),
        KeywordEntry("男の娘", "伪娘", "人物", true),
        KeywordEntry("女装", "女装", "人物", true),
        KeywordEntry("BL", "男性恋爱", "题材", true),
        KeywordEntry("ボーイズラブ", "男性恋爱", "题材", true),
        KeywordEntry("ボーイズラブ要素あり", "含男性恋爱要素", "题材", true),
        KeywordEntry("ボーイズラブあり", "含男性恋爱", "题材", true),
        KeywordEntry("BL要素あり", "含男性恋爱要素", "题材", true),
        KeywordEntry("耽美", "耽美", "题材", true),
        KeywordEntry("GL", "百合", "题材", true),
        KeywordEntry("ガールズラブ", "百合", "题材", true),
        KeywordEntry("ガールズラブ要素あり", "含百合要素", "题材", true),
        KeywordEntry("ガールズラブあり", "含百合", "题材", true),
        KeywordEntry("百合", "百合", "题材", true),
        KeywordEntry("百合要素あり", "含百合要素", "题材", true),
        KeywordEntry("GL要素あり", "含百合要素", "题材", true),
        KeywordEntry("TS", "性转", "题材", true),
        KeywordEntry("TSF", "性别转换幻想", "题材", true),
        KeywordEntry("性転換", "性转", "情节", true),
        KeywordEntry("性別転換", "性别转换", "情节", true),
        KeywordEntry("女体化", "变为女性", "情节", true),
        KeywordEntry("男体化", "变为男性", "情节", true),
        KeywordEntry("TS転生", "性转转生", "情节", true),
        KeywordEntry("TS転移", "性转穿越", "情节", true),
        KeywordEntry("TS要素あり", "含性转要素", "题材", true),
    )
    private val previousByName = previousCommon.associateBy { normalizeKeyword(it.original) }
    private val categoriesByName = keywordCategorySeeds.flatMap { seed ->
        seed.originals.map { normalizeKeyword(it) to seed.name }
    }.toMap()
    val common = (previousCommon + SiteKeywordTranslations.mappings.map { (jp, zh) -> KeywordEntry(jp, zh, common = true) })
        .distinctBy { it.original }.map { entry ->
            entry.copy(translation = SiteKeywordTranslations.translate(entry.original) ?: entry.translation,
                category = categoriesByName[normalizeKeyword(entry.original)] ?: entry.category)
        }
    private val aliases = mapOf(
        "ほのぼの" to listOf("日常", "温馨", "治愈"),
        "ハッピーエンド" to listOf("HappyEnd", "HE", "好结局"),
        "バッドエンド" to listOf("BadEnd", "BE", "坏结局"),
        "チート" to listOf("开挂"),
        "ラブコメ" to listOf("恋爱喜剧"),
    )
    private val defaultsByName = common.associateBy { normalizeKeyword(it.original) }
    private val categoryNames = keywordCategorySeeds.flatMap { category ->
        category.originals.map { normalizeKeyword(it) to (category.originals + category.name) }
    }.toMap()

    private fun defaultTranslation(original: String): String = SiteKeywordTranslations.translate(original)
        ?: defaultsByName[normalizeKeyword(original)]?.translation.orEmpty()

    /** 原站组合标签也可直接显示译名，不依赖是否已收集进本地词库。显式清空仍受保护。 */
    fun displayTranslation(entry: KeywordEntry): String {
        if(entry.translationEdited) return entry.translation
        val previous = previousByName[normalizeKeyword(entry.original)]
        return if(entry.translation.isBlank() || entry.translation == previous?.translation)
            defaultTranslation(entry.original).ifBlank { entry.translation } else entry.translation
    }

    internal fun seededCategory(entry: KeywordEntry): String? = categoriesByName[normalizeKeyword(entry.original)]?.takeIf {
        !entry.categoryEdited && (entry.category == "其他" || entry.category == previousByName[normalizeKeyword(entry.original)]?.category)
    }

    /** 已有条目始终优先于默认值，包括用户显式设为空的翻译。 */
    fun withDefaults(entries: Collection<KeywordEntry>): List<KeywordEntry> =
        normalize(fillMissingTranslations(entries) + common)

    /** 补齐原站译名并更新旧默认映射，保留自定义译名、显式清空与分类。 */
    fun fillMissingTranslations(entries: Collection<KeywordEntry>): List<KeywordEntry> = entries.map { entry ->
        entry.copy(translation = displayTranslation(entry))
    }

    /** 仅校验与去重，不因数量或使用频率丢弃已有词条。 */
    fun normalize(entries: Collection<KeywordEntry>): List<KeywordEntry> =
        entries.filter { it.original.isNotBlank() && it.original.length <= MAX_TEXT_LENGTH && it.translation.length <= MAX_TEXT_LENGTH }
            .distinctBy { it.original }

    /** 调低上限不删除词条，达到或超过上限时仍允许编辑已有词条。 */
    fun requireCapacity(currentCount: Int, nextCount: Int, limit: Int?) {
        require(limit == null || limit > 0) { "标签数量上限需为正整数或不限" }
        require(limit == null || nextCount <= limit || nextCount <= currentCount) {
            "标签数量将超过设置的上限 $limit 个，未添加新标签；请在设置中调整标签数量上限"
        }
    }

    fun observe(entries: List<KeywordEntry>, originals: Collection<String>, limit: Int? = null): List<KeywordEntry> {
        requireCapacity(entries.size, entries.size, limit)
        val known = entries.mapTo(mutableSetOf()) { it.original }
        val remaining = limit?.let { (it - entries.size).coerceAtLeast(0) } ?: Int.MAX_VALUE
        val added = originals.asSequence().map(String::trim).filter { it.isNotBlank() && it.length <= MAX_TEXT_LENGTH && known.add(it) }
            .take(remaining).map { original -> defaultsByName[normalizeKeyword(original)]?.copy(original = original)
                ?: KeywordEntry(original, defaultTranslation(original)) }.toList()
        return if(added.isEmpty()) entries else entries + added
    }

    fun markUsed(entries: List<KeywordEntry>, originals: Collection<String>, now: Long, limit: Int? = null): List<KeywordEntry> {
        val selected = originals.map(String::trim).filter { it.isNotBlank() && it.length <= MAX_TEXT_LENGTH }.toSet()
        if(selected.isEmpty()) return entries
        val observed = observe(entries, selected, limit)
        return observed.map { if(it.original in selected) it.copy(lastUsedAt = now) else it }
    }

    fun translate(entries: List<KeywordEntry>, original: String, translation: String, limit: Int? = null): List<KeywordEntry> {
        require(original.isNotBlank() && original.length <= MAX_TEXT_LENGTH && translation.length <= MAX_TEXT_LENGTH) { "标签原文和翻译最多 $MAX_TEXT_LENGTH 字符" }
        requireCapacity(entries.size, entries.size + if(entries.any { it.original == original }) 0 else 1, limit)
        val existing = entries.firstOrNull { it.original == original } ?: common.firstOrNull { it.original == original } ?: KeywordEntry(original)
        return normalize(entries.filterNot { it.original == original } + existing.copy(translation = translation, translationEdited = true))
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
        return if(addDefaults) withDefaults(merged + incoming) else normalize(merged + incoming)
    }

    /** 完全匹配优先，其次是前缀和包含匹配；同分时优先最近使用及常用词条。 */
    fun suggestions(entries: List<KeywordEntry>, query: String, category: String = "全部", limit: Int = 20): List<KeywordEntry> {
        val normalized = normalizeKeyword(query)
        fun score(entry: KeywordEntry): Int {
            if(normalized.isEmpty()) return 0
            val names = listOf(entry.original, entry.displayTranslation, entry.category) +
                listOfNotNull(defaultsByName[normalizeKeyword(entry.original)]?.translation) + aliases[entry.original].orEmpty() +
                categoryNames[normalizeKeyword(entry.original)].orEmpty()
            return names.filter(String::isNotBlank).minOfOrNull { name ->
                val value = normalizeKeyword(name)
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
            (listOf(entry.original, entry.displayTranslation) + listOfNotNull(common.firstOrNull { it.original == entry.original }?.translation) +
                aliases[entry.original].orEmpty()).any { it.equals(text, ignoreCase = true) }
        }
    }

    /** 上游解析器只移除末尾一个 '$'，原词内部或末尾已有的 '$' 可被保留。 */
    fun canSearch(original: String): Boolean = original.isNotBlank() && original.length <= MAX_TEXT_LENGTH && original.none(Char::isWhitespace) &&
        !original.startsWith('-')
}
