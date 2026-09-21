package cc.novelia.app.data.catalog


/**
 * 将辅助搜索表单转成原站查询表达式，而不是在客户端执行搜索。
 * 普通词和短语需转义查询运算符，标签使用站点约定的尾随美元符号，数量条件使用比较前缀。
 * 原站先按空格预处理再解析查询，因此美元符号、引号和空格的处理顺序必须保留。
 */
object SearchExpression {
    private fun escape(value: String) = value.replace(Regex("[+|\\-\"()*~:<>\\\\]")) { "\\${it.value}" }
    // The site's space-split preprocessing runs before the Lucene simple-query parser.
    private fun word(value: String): String = escape(value).let { if(value.endsWith('$')) "\"$it\"" else it }
    private fun phrase(value: String): String = escape(value).replace("$ ", "$\\ ")
    fun build(all: String, any: String, exact: String, excluded: String, tags: String, excludedTags: String, minimum: String, maximum: String): String {
        fun words(text: String) = text.split(Regex("\\s+")).filter(String::isNotBlank)
        return buildList {
            addAll(words(all).map(::word))
            words(any).takeIf { it.isNotEmpty() }?.let { add(it.joinToString(" | ", "(", ")", transform = ::word)) }
            exact.trim().takeIf(String::isNotEmpty)?.let { add("\"${phrase(it)}\"") }
            addAll(words(excluded).map { "-${word(it)}" })
            val excludedKeywords = words(excludedTags).filter(KeywordCatalog::canSearch).distinct()
            addAll(words(tags).filter(KeywordCatalog::canSearch).distinct().filterNot { it in excludedKeywords }.map { "$it$" })
            addAll(excludedKeywords.map { "-$it$" })
            minimum.toIntOrNull()?.takeIf { it >= 0 }?.let { add(">$it") }
            maximum.toIntOrNull()?.takeIf { it >= 0 }?.let { add("<$it") }
        }.joinToString(" ")
    }

    fun tagsIn(expression: String): List<String> = expression.split(' ').filter { it.endsWith('$') }
        .map { it.removePrefix("-").removeSuffix("$") }.filter(KeywordCatalog::canSearch).distinct()

    /** 追加辅助条件时保留手写表达式原貌，只去除已存在的同向标签；不尝试重写完整查询语法。 */
    fun append(existing: String, generated: String): String {
        val present = existing.split(' ').filter { it.endsWith('$') }.toSet()
        val additional = generated.split(' ').filterNot { it.endsWith('$') && it in present }.joinToString(" ").trim()
        return listOf(existing.trim(), additional).filter(String::isNotEmpty).joinToString(" ")
    }

    fun conflictingTags(existing: String, generated: String): List<String> {
        val present = existing.split(' ').filter { it.endsWith('$') }.toSet()
        return generated.split(' ').filter { it.endsWith('$') }.filter { token ->
            (if(token.startsWith('-')) token.removePrefix("-") else "-$token") in present
        }.map { it.removePrefix("-").removeSuffix("$") }.distinct()
    }
}
