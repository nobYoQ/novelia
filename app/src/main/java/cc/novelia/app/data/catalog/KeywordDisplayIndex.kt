package cc.novelia.app.data.catalog

/** 在后台构建并共享，Compose 只查询当前页面需要的标签。 */
data class KeywordDisplayIndex(
    val entries: Map<String, KeywordEntry> = emptyMap(),
    val labels: Map<String, String> = emptyMap(),
) {
    companion object {
        fun build(entries: List<KeywordEntry>): KeywordDisplayIndex = KeywordDisplayIndex(
            entries.associateBy { it.original },
            entries.associate { it.original to it.displayTranslation.ifBlank { it.original } },
        )
    }
}
