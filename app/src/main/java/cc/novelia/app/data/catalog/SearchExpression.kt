package cc.novelia.app.data.catalog


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

    /** Do not parse or rewrite a manually composed expression when adding assisted conditions. */
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
