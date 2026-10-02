package cc.novelia.app.data.catalog

import java.text.Normalizer
import java.util.Locale

internal fun normalizeKeyword(value: String) = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)

/** 仅用于初次建立可编辑的分类，以及将旧版固定分组迁移为普通标签条件。 */
internal data class KeywordCategorySeed(val legacyId: String, val name: String, val originals: List<String>)

internal val keywordCategorySeeds = listOf(
    KeywordCategorySeed("bl", "BL／男性恋爱", listOf("BL", "ボーイズラブ", "ボーイズラブ要素あり", "ボーイズラブあり", "BL要素あり", "耽美")),
    KeywordCategorySeed("gl", "GL／百合", listOf("GL", "ガールズラブ", "ガールズラブ要素あり", "ガールズラブあり", "百合", "百合要素あり", "GL要素あり")),
    KeywordCategorySeed("ts", "TS／性转", listOf("TS", "TSF", "性転換", "性別転換", "女体化", "男体化", "TS転生", "TS転移", "TS要素あり")),
)
