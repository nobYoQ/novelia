package cc.novelia.app.data.model

const val MAX_READER_REPLACEMENTS = 1000
const val MAX_READER_REPLACEMENT_LENGTH = 120

fun validReaderTextReplacements(rules: Map<String, String>): Boolean =
    rules.size <= MAX_READER_REPLACEMENTS && rules.all { (from, to) ->
        listOf(from, to).all { it.isNotBlank() && it.length <= MAX_READER_REPLACEMENT_LENGTH && it.none(Char::isISOControl) }
    } && rules.entries.sumOf { it.key.length + it.value.length } <= 100_000
