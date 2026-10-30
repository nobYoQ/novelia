package cc.novelia.app.files.epub

import cc.novelia.app.data.model.LocalBilingualGroup
import cc.novelia.app.data.model.LocalChapter
import cc.novelia.app.data.model.LocalReadingContent
import cc.novelia.app.data.model.InlineContent
import cc.novelia.app.data.model.InlineKind
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeVisitor

internal const val EPUB_CONTENT_VERSION = 3
private val epubBlocks = setOf("h1", "h2", "h3", "h4", "p", "li", "blockquote", "pre", "div", "section", "br")
private val opacityStyle = Regex("(?:^|;)\\s*opacity\\s*:\\s*(0?\\.\\d+|1(?:\\.0+)?)\\s*(?:!important\\s*)?(?:;|$)", RegexOption.IGNORE_CASE)

/** 两种导入路径共用 DOM 切段，保留平铺正文以维持原文件导出与旧笔记的下标。 */
internal fun parseEpubChapter(
    id: String, html: String, downloadMode: String?, image: (String) -> String?,
    checkCancelled: () -> Unit = {}, onText: (String) -> Unit = {},
    note: (String, Boolean) -> Pair<String, String>? = { _, _ -> null },
): LocalChapter? {
    val doc = Jsoup.parse(html)
    doc.select("script,style,nav").remove()
    val ruby = prepareEpubRuby(doc, checkCancelled)
    val paragraphs = mutableListOf<String>()
    val inline = linkedMapOf<Int, List<InlineContent>>()
    val notes = linkedMapOf<String, String>()
    var noteCharacters = 0
    val active = linkedMapOf<Element, List<InlineContent>>()
    val completed = mutableListOf<InlineContent>()
    val owners = mutableListOf<Element?>()
    val buffer = StringBuilder()
    var owner: Element? = null
    var current: Element? = null
    var preformatted = 0
    fun flush() {
        val raw = buffer.toString()
        val ranges = completed + active.values.flatten().map { it.copy(end = raw.length) }
        val (text, marks) = normalizeEpubInline(raw, ranges, preformatted > 0, checkCancelled)
        if (text.isNotEmpty()) {
            onText(text)
            if (marks.isNotEmpty()) inline[paragraphs.size] = marks
            paragraphs += text
            owners += owner
        }
        buffer.clear()
        completed.clear()
        active.replaceAll { _, marks -> marks.map { it.copy(start = 0) } }
        owner = null
    }
    doc.body().traverse(object : NodeVisitor {
        override fun head(node: Node, depth: Int) {
            checkCancelled()
            when (node) {
                is TextNode -> if (!node.isBlank || buffer.isNotEmpty()) {
                    if (buffer.isEmpty()) owner = current
                    buffer.append(node.wholeText)
                }
                is Element -> {
                    if (node.tagName() in epubBlocks) flush()
                    if (node.tagName() == "pre") preformatted++
                    if (node.tagName() == "p") current = node
                    val marks = node.epubStyles().map { InlineContent(buffer.length, buffer.length, it) }.toMutableList()
                    ruby[node]?.let { marks += InlineContent(buffer.length, buffer.length, InlineKind.Ruby, it) }
                    if (node.normalName() == "a" && node.hasAttr("href")) {
                        val explicit = "noteref" in node.epubTypes() || node.attr("role").split(' ').contains("doc-noteref")
                        note(node.attr("href"), explicit)?.let { (target, body) ->
                            if (target in notes || noteCharacters + body.length <= 2 * 1024 * 1024) {
                                if (notes.putIfAbsent(target, body) == null) noteCharacters += body.length
                                marks += InlineContent(buffer.length, buffer.length, InlineKind.Note, target)
                            }
                        }
                    }
                    if (marks.isNotEmpty()) active[node] = marks
                    if (node.tagName() in setOf("img", "image")) {
                        flush()
                        val source = node.attr("src").ifBlank { node.attr("xlink:href").ifBlank { node.attr("href") } }
                        image(source)?.let { paragraphs += "novelia-image:$it"; owners += null }
                    }
                }
            }
        }
        override fun tail(node: Node, depth: Int) {
            if (node is Element) active.remove(node)?.let { marks -> completed += marks.map { it.copy(end = buffer.length) } }
            if (node is Element && node.tagName() in epubBlocks) flush()
            if (node is Element && node.tagName() == "pre") preformatted--
            if (node is Element && node.tagName() == "p") current = null
        }
    })
    flush()
    if (paragraphs.isEmpty()) return null
    val indices = linkedMapOf<Element, MutableList<Int>>()
    owners.forEachIndexed { index, element -> if (element != null) indices.getOrPut(element) { mutableListOf() } += index }
    fun Element.language() = attr("lang").ifBlank { attr("xml:lang") }.lowercase().substringBefore('-')
    fun Element.opacity() = opacityStyle.find(attr("style"))?.groupValues?.get(1)?.toFloatOrNull()
    fun Element.original() = language() == "ja" ||
        (downloadMode in setOf("jp-zh", "zh-jp") && language() != "zh" && opacity() == .4f)
    fun Element.translation() = tagName() == "p" && !original() &&
        (language() == "zh" || (downloadMode in setOf("jp-zh", "zh-jp") && attributes().isEmpty))
    val groups = mutableListOf<LocalBilingualGroup>()
    val used = mutableSetOf<Int>()
    for ((element, original) in indices) {
        checkCancelled()
        if (!element.original()) continue
        // 已知下载方向才认无语言属性的插入段；外部 EPUB 要求显式 ja/zh 且方向不歧义。
        val before = element.previousElementSibling()?.translation() == true
        val after = element.nextElementSibling()?.translation() == true
        val forward = when (downloadMode) {
            "jp-zh" -> true
            "zh-jp" -> false
            else -> if (before == after) continue else after
        }
        val translations = mutableListOf<List<Int>>()
        var sibling = if (forward) element.nextElementSibling() else element.previousElementSibling()
        while (sibling != null && sibling.translation()) {
            checkCancelled()
            if (sibling.selectFirst("img,image,svg") != null) break
            indices[sibling]?.let { translations += it }
            sibling = if (forward) sibling.nextElementSibling() else sibling.previousElementSibling()
        }
        if (!forward) translations.reverse()
        if (translations.isEmpty() || element.selectFirst("img,image,svg") != null) continue
        val all = (original + translations.flatten()).sorted()
        // 不跨图片、标题、块外正文或另一组配对，避免把不相关内容藏进某一语言。
        if (all.any { it in used } || all.last() - all.first() + 1 != all.size) continue
        groups += LocalBilingualGroup(original.toList(), translations.map { it.toList() })
        used += all
    }
    val secondary = indices.filterKeys { it.opacity()?.let { opacity -> opacity < 1f } == true }.values.flatten()
    val title = doc.selectFirst("h1,h2,h3,title")?.text()?.takeIf(String::isNotBlank) ?: "章节"
    return LocalChapter(id, title, paragraphs, LocalReadingContent(groups, secondary, inline, notes), EPUB_CONTENT_VERSION, downloadMode)
}
