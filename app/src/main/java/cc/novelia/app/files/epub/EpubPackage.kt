package cc.novelia.app.files.epub

import cc.novelia.app.data.model.EpubLinkTarget
import cc.novelia.app.data.model.EpubNavigationEntry
import java.net.URLDecoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

internal const val EPUB_TITLE_VERSION = 3
internal const val MAX_EPUB_XML_BYTES = 8 * 1024 * 1024
private const val OCF_NS = "urn:oasis:names:tc:opendocument:xmlns:container"
private const val OPF_NS = "http://www.idpf.org/2007/opf"
private const val NCX_NS = "http://www.daisy.org/z3986/2005/ncx/"
private const val XHTML_NS = "http://www.w3.org/1999/xhtml"
private const val DC_NS = "http://purl.org/dc/elements/1.1/"
private val propertySeparator = Regex("\\s+")

internal data class EpubSpineItem(
    val id: String,
    val sourcePath: String?,
    val contentPath: String?,
    val linear: Boolean,
)

internal class EpubPackage(
    val spine: List<EpubSpineItem>,
    val coverPath: String?,
    private val bookTitle: String?,
    navPath: String?,
    ncxPath: String?,
    navigationText: (String) -> String?,
    checkCancelled: () -> Unit,
) {
    // 重复的 spine 条目各自可阅读和标记；超链接进入同一资源的第一个线性条目。
    private val idsByPath = buildMap {
        val linearFirst = spine.partition { it.linear }.let { (linear, auxiliary) -> linear + auxiliary }
        for (item in linearFirst) item.sourcePath?.let { putIfAbsent(it, item.id) }
        for (item in linearFirst) item.contentPath?.let { putIfAbsent(it, item.id) }
    }
    private val nav = navPath?.let { source -> navigationText(source)?.let { readNav(source, it, checkCancelled) } }.orEmpty()
    private val ncx = ncxPath?.let { source -> navigationText(source)?.let { readNcx(source, it, checkCancelled) } }.orEmpty()
    private val titles = buildMap {
        for (entry in nav.asSequence() + ncx.asSequence()) entry.target?.let { putIfAbsent(it.chapterId, entry.title) }
    }

    fun linkTarget(source: String, href: String, fragmentSource: String = source): EpubLinkTarget? {
        val reference = href.trim().takeIf(String::isNotEmpty) ?: return null
        val path = reference.substringBefore('#')
        val fragment = decodeEpubReference(reference.substringAfter('#', ""))
            ?.takeIf { it.length in 1..512 && it.none(Char::isISOControl) }
        val resource = if (path.isEmpty()) fragmentSource else resolveEpubResource(source.substringBeforeLast('/', ""), path) ?: return null
        val id = idsByPath[resource] ?: return null
        return EpubLinkTarget(id, fragment)
    }

    fun tocTitle(item: EpubSpineItem): String? =
        titles[item.id] ?: titles[idsByPath[item.sourcePath]] ?: titles[idsByPath[item.contentPath]]

    fun chapterTitle(item: EpubSpineItem, doc: Document): String =
        tocTitle(item) ?: epubChapterTitle(doc, bookTitle, item.contentPath?.substringAfterLast('/')?.substringBeforeLast('.'))

    fun navigation(readableIds: Set<String>): List<EpubNavigationEntry> {
        fun available(entries: List<EpubNavigationEntry>) = entries.filter { it.target == null || it.target.chapterId in readableIds }
        return available(nav).takeIf { entries -> entries.any { it.target != null } } ?: available(ncx)
    }

    private fun readNav(source: String, html: String, checkCancelled: () -> Unit): List<EpubNavigationEntry> {
        val nav = Jsoup.parse(html, "", Parser.xmlParser()).getAllElements().firstOrNull {
            it.isEpubElement("nav", XHTML_NS) && "toc" in it.epubTypes()
        } ?: return emptyList()
        fun Element.items() = epubChild("ol", XHTML_NS)?.epubChildren("li", XHTML_NS).orEmpty()
        return readNavigation(nav.items(), checkCancelled, { it.items() }) { item, depth, hasChildren ->
            val label = item.children().firstOrNull { it.isEpubElement("a", XHTML_NS) || it.isEpubElement("span", XHTML_NS) }
            val target = label?.takeIf { it.isEpubElement("a", XHTML_NS) }?.let { linkTarget(source, it.attr("href")) }
            val title = label?.epubTitle()
            if (title != null && (target != null || hasChildren)) EpubNavigationEntry(title, target, depth) else null
        }
    }

    private fun readNcx(source: String, xml: String, checkCancelled: () -> Unit): List<EpubNavigationEntry> {
        val map = Jsoup.parse(xml, "", Parser.xmlParser()).getAllElements().firstOrNull { it.isEpubElement("navMap", NCX_NS) }
            ?: return emptyList()
        fun Element.items() = epubChildren("navPoint", NCX_NS)
        return readNavigation(map.items(), checkCancelled, { it.items() }) { point, depth, hasChildren ->
            val target = point.epubChild("content", NCX_NS)?.let { linkTarget(source, it.attr("src")) }
            val title = point.epubChild("navLabel", NCX_NS)?.epubChild("text", NCX_NS)?.epubTitle()
            if (title != null && (target != null || hasChildren)) EpubNavigationEntry(title, target, depth) else null
        }
    }

    private fun readNavigation(
        roots: List<Element>,
        checkCancelled: () -> Unit,
        children: (Element) -> List<Element>,
        entry: (Element, Int, Boolean) -> EpubNavigationEntry?,
    ): List<EpubNavigationEntry> = buildList {
        val pending = ArrayDeque(roots.asReversed().map { it to 0 })
        while (pending.isNotEmpty()) {
            checkCancelled()
            val (node, depth) = pending.removeLast()
            val nested = children(node)
            val item = entry(node, depth, nested.isNotEmpty())
            item?.let { add(it) }
            val childDepth = if (item == null) depth else depth + 1
            nested.asReversed().forEach { pending.addLast(it to childDepth) }
        }
    }
}

internal fun epubChapterTitle(doc: Document, bookTitle: String? = null, fileStem: String? = null): String {
    doc.body().select("h1,h2,h3,h4,h5,h6").firstNotNullOfOrNull { it.epubTitle() }?.let { return it }
    val title = doc.head().selectFirst("title")?.epubTitle() ?: return "章节"
    return when (title) {
        fileStem -> ""
        bookTitle -> "章节"
        else -> title
    }
}

private data class EpubManifestItem(val path: String?, val mediaType: String, val fallback: String) {
    fun canReadAsChapter() = when (mediaType) {
        "application/xhtml+xml", "text/html" -> true
        "" -> path?.substringAfterLast('.')?.lowercase() in setOf("xhtml", "html", "htm")
        else -> false
    }
}

/** spine 决定阅读顺序，NAV/NCX 独立保存作者目录及锚点。 */
internal fun readEpubPackage(
    text: (String) -> String?,
    navigationText: (String) -> String?,
    hasResource: (String) -> Boolean,
    checkCancelled: () -> Unit = {},
): EpubPackage {
    val container = text("META-INF/container.xml") ?: error("EPUB 缺少 container.xml")
    val opfPath = Jsoup.parse(container, "", Parser.xmlParser()).getAllElements().firstOrNull { it.isEpubElement("rootfile", OCF_NS) }?.attr("full-path")
        ?.let { resolveEpubResource("", it) } ?: error("EPUB 未指定内容文件")
    val opf = Jsoup.parse(text(opfPath) ?: error("EPUB 缺少 OPF"), "", Parser.xmlParser()).getAllElements()
        .firstOrNull { it.isEpubElement("package", OPF_NS) } ?: error("EPUB 的 OPF 缺少 package")
    val directory = opfPath.substringBeforeLast('/', "")
    val manifest = opf.epubChild("manifest", OPF_NS)?.epubChildren("item", OPF_NS).orEmpty()
    val resources = manifest.associate { item ->
        checkCancelled()
        item.attr("id") to EpubManifestItem(resolveEpubResource(directory, item.attr("href")), item.attr("media-type"), item.attr("fallback"))
    }
    val resolved = mutableMapOf<String, String?>()
    fun contentPath(id: String): String? {
        val visited = linkedSetOf<String>()
        var current = id
        var path: String? = null
        while (current.isNotEmpty() && visited.add(current)) {
            checkCancelled()
            if (current in resolved) {
                path = resolved[current]
                break
            }
            val item = resources[current] ?: break
            if (item.canReadAsChapter() && item.path != null && hasResource(item.path)) {
                path = item.path
                break
            }
            current = item.fallback
        }
        for (visitedId in visited) resolved[visitedId] = path
        return path
    }
    val spine = opf.epubChild("spine", OPF_NS)
    val usedIds = resources.keys.toMutableSet()
    val occurrences = mutableMapOf<String, Int>()
    val items = spine?.epubChildren("itemref", OPF_NS).orEmpty().mapNotNull { item ->
        checkCancelled()
        val id = item.attr("idref").takeIf(String::isNotEmpty) ?: return@mapNotNull null
        val resource = resources[id] ?: return@mapNotNull null
        var occurrence = (occurrences[id] ?: 0) + 1
        var chapterId = id
        if (occurrence > 1) {
            chapterId = "$id~$occurrence"
            while (!usedIds.add(chapterId)) chapterId = "$id~${++occurrence}"
        }
        occurrences[id] = occurrence
        EpubSpineItem(chapterId, resource.path, contentPath(id), item.attr("linear") != "no")
    }
    val navPath = resources[manifest.firstOrNull { it.hasEpubProperty("nav") }?.attr("id")]?.path
    val ncxPath = resources[spine?.attr("toc")]?.path ?: resources.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }?.path
    val metadata = opf.epubChild("metadata", OPF_NS)
    val bookTitle = metadata?.epubChild("title", DC_NS)?.epubTitle()
    val coverId = manifest.firstOrNull { it.hasEpubProperty("cover-image") }?.attr("id")
        ?: metadata?.epubChildren("meta", OPF_NS)?.firstOrNull { it.attr("name") == "cover" }?.attr("content")
    return EpubPackage(items, resources[coverId]?.path, bookTitle, navPath, ncxPath, navigationText, checkCancelled)
}

internal fun Element.epubNamespace(prefix: String): String? {
    val declaration = if (prefix.isEmpty()) "xmlns" else "xmlns:$prefix"
    return generateSequence(this, Element::parent).firstOrNull { it.hasAttr(declaration) }?.attr(declaration)
}

private fun Element.isEpubElement(local: String, namespace: String): Boolean {
    val tag = tagName()
    if (tag.substringAfter(':') != local) return false
    val prefix = tag.substringBefore(':', "")
    val declared = epubNamespace(prefix)
    return declared == namespace || (prefix.isEmpty() && declared.isNullOrEmpty())
}

private fun Element.epubChildren(local: String, namespace: String) = children().filter { it.isEpubElement(local, namespace) }
private fun Element.epubChild(local: String, namespace: String) = children().firstOrNull { it.isEpubElement(local, namespace) }

private fun Element.hasEpubProperty(property: String) = property in attr("properties").split(propertySeparator)

private fun Element.epubTitle(): String? {
    val label = clone()
    label.select("script,style,rt,rp,rtc").remove()
    return label.text().takeIf { it.isNotBlank() && '\uFFFD' !in it }
}

private val importantStyle = Regex("!\\s*important\\s*$", RegexOption.IGNORE_CASE)

private fun Element.hiddenInEpubBody(): Boolean {
    if (hasAttr("hidden")) return true
    var display = ""
    var important = false
    for (declaration in attr("style").split(';')) {
        if (!declaration.substringBefore(':').trim().equals("display", ignoreCase = true)) continue
        val value = declaration.substringAfter(':', "").trim()
        val priority = importantStyle.find(value)
        if (important && priority == null) continue
        display = value.substring(0, priority?.range?.first ?: value.length).trim()
        important = priority != null
    }
    return display.equals("none", ignoreCase = true)
}

internal fun epubChapterDocument(html: String, preserveLegacyBody: Boolean = false): Document = Jsoup.parse(html).apply {
    select("script,style").remove()
    select("nav").filter { "toc" !in it.epubTypes() }.forEach { it.remove() }
    if (!preserveLegacyBody) select("[hidden],[style]").filter { it.hiddenInEpubBody() }.forEach { it.remove() }
}

internal fun resolveEpubResource(directory: String, href: String): String? {
    val reference = href.trim().substringBefore('#')
    val path = decodeEpubReference(reference) ?: return null
    if (path.isBlank() || path.startsWith('/') || path.any { it in ":\\?\u0000" }) return null
    return normalizeEpubPath(if (directory.isEmpty()) path else "$directory/$path")
}

private fun decodeEpubReference(value: String): String? = try { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }
catch (_: IllegalArgumentException) { null }

/** EPUB 的 XML 资源支持 UTF-8 和 UTF-16。两条导入路径共用解码规则。 */
internal fun decodeEpubXml(bytes: ByteArray): String {
    val first = bytes.getOrNull(0)?.toInt()?.and(0xff)
    val second = bytes.getOrNull(1)?.toInt()?.and(0xff)
    val charset = when {
        (first == 0xfe && second == 0xff) || (first == 0xff && second == 0xfe) -> Charsets.UTF_16
        first == 0 -> Charsets.UTF_16BE
        second == 0 -> Charsets.UTF_16LE
        else -> Charsets.UTF_8
    }
    return bytes.toString(charset).removePrefix("\uFEFF")
}
