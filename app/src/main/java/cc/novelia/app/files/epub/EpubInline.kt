package cc.novelia.app.files.epub

import cc.novelia.app.data.model.InlineContent
import cc.novelia.app.data.model.InlineKind
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** 只解释白名单行内语义，不执行 CSS，也不加载外部资源。 */
internal fun Element.epubStyles(): List<InlineKind> = buildList {
    when (normalName()) {
        "b", "strong" -> add(InlineKind.Bold)
        "i", "em" -> add(InlineKind.Italic)
        "u" -> add(InlineKind.Underline)
        "s", "strike", "del" -> add(InlineKind.Strike)
    }
    attr("style").lowercase().split(';').forEach { declaration ->
        val key = declaration.substringBefore(':').trim()
        val value = declaration.substringAfter(':', "").substringBefore("!important").trim()
        when (key) {
            "font-weight" -> if (value == "bold" || value == "bolder" || (value.toIntOrNull() ?: 0) >= 600) add(InlineKind.Bold)
            "font-style" -> if (value in setOf("italic", "oblique")) add(InlineKind.Italic)
            "text-decoration", "text-decoration-line" -> {
                if ("underline" in value.split(' ')) add(InlineKind.Underline)
                if ("line-through" in value.split(' ')) add(InlineKind.Strike)
            }
        }
    }
}.distinct()

internal fun Element.epubTypes() = attr("epub:type").split(Regex("\\s+")).toSet()
internal fun Element.isEpubNote() = epubTypes().any { it in setOf("footnote", "endnote") } ||
    attr("role").split(' ').any { it in setOf("doc-footnote", "doc-endnote") }

/** 将常见逐字/整词 ruby 变成可附着区间的底文元素；rt/rp 仍不进入正文。 */
internal fun prepareEpubRuby(root: Element, checkCancelled: () -> Unit): Map<Element, String> {
    val result = linkedMapOf<Element, String>()
    for (ruby in root.select("ruby").reversed()) {
        checkCancelled()
        if (ruby.select("ruby").size > 1) continue // 嵌套 Ruby 保留内层，避免同一区间叠画。
        val bases = ruby.children().filter { it.normalName() == "rb" }
        val readings = ruby.children().filter { it.normalName() == "rt" }
        if (bases.isNotEmpty() && bases.size == readings.size) {
            bases.zip(readings).forEach { (base, reading) ->
                normalizeEpubWhitespace(reading.text()).takeIf { it.isNotBlank() }?.let { result[base] = it }
            }
        } else {
            val pending = mutableListOf<Node>()
            for (child in ruby.childNodes().toList()) {
                if (child is Element && child.normalName() == "rt") {
                    val reading = normalizeEpubWhitespace(child.text())
                    if (pending.isNotEmpty() && reading.isNotBlank()) {
                        val wrapper = Element("span")
                        pending.first().before(wrapper)
                        pending.forEach { wrapper.appendChild(it) }
                        result[wrapper] = reading
                    }
                    pending.clear()
                } else if (child !is Element || child.normalName() !in setOf("rp", "rtc")) pending += child
            }
        }
    }
    root.select("rt,rp,rtc").remove()
    return result
}

internal fun normalizeEpubInline(raw: String, inline: List<InlineContent>, preformatted: Boolean,
    checkCancelled: () -> Unit): Pair<String, List<InlineContent>> {
    val offsets = if (inline.isEmpty()) null else IntArray(raw.length + 1)
    val text = if (preformatted) {
        val trimmed = raw.trim()
        val leading = raw.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) raw.length else it }
        offsets?.indices?.forEach { offsets[it] = (it - leading).coerceIn(0, trimmed.length) }
        trimmed
    } else normalizeEpubWhitespace(raw, checkCancelled, offsets)
    return text to if (offsets == null) emptyList() else inline.map {
        it.copy(start = offsets[it.start], end = offsets[it.end])
    }.filter { it.validFor(text) }.distinct()
}
