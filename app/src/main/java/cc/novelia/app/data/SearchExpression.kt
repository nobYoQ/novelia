package cc.novelia.app.data

object SearchExpression {
    private fun escape(value: String) = value.replace(Regex("[+|\\-\"()*~:\\\\]")) { "\\${it.value}" }
    fun build(all: String, any: String, exact: String, excluded: String, tags: String, excludedTags: String, minimum: String, maximum: String): String {
        fun words(text: String) = text.split(Regex("\\s+")).filter(String::isNotBlank)
        return buildList {
            addAll(words(all).map(::escape))
            words(any).takeIf { it.isNotEmpty() }?.let { add(it.joinToString(" | ", "(", ")", transform = ::escape)) }
            exact.trim().takeIf(String::isNotEmpty)?.let { add("\"${escape(it)}\"") }
            addAll(words(excluded).map { "-${escape(it)}" })
            addAll(words(tags).map { "${it.removeSuffix("$")}$" })
            addAll(words(excludedTags).map { "-${it.removePrefix("-").removeSuffix("$")}$" })
            minimum.toIntOrNull()?.takeIf { it >= 0 }?.let { add(">$it") }
            maximum.toIntOrNull()?.takeIf { it >= 0 }?.let { add("<$it") }
        }.joinToString(" ")
    }
}
