package cc.novelia.app.data.catalog

/** 分类操作只展开当前成员，之后每个标签独立编辑，不保存分类与条件的绑定。 */
data class KeywordSelection(val included: List<String> = emptyList(), val excluded: List<String> = emptyList()) {
    fun add(originals: Collection<String>, include: Boolean): KeywordSelection {
        val selected = originals.filter(KeywordCatalog::canSearch).distinct()
        val selectedSet = selected.toSet()
        return if(include) copy(included = (included + selected).distinct(), excluded = excluded.filterNot { it in selectedSet })
        else copy(excluded = (excluded + selected).distinct(), included = included.filterNot { it in selectedSet })
    }
}
