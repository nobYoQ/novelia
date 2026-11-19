package cc.novelia.app.files.epub

import java.net.URLDecoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

internal const val EPUB_TITLE_VERSION = 1
internal const val MAX_EPUB_XML_BYTES = 8 * 1024 * 1024
private val propertySeparator = Regex("\\s+")

internal data class EpubSpineItem(val id: String, val path: String)

internal class EpubPackage(
    val spine: List<EpubSpineItem>,
    val coverPath: String?,
    private val bookTitle: String?,
    private val toc: Map<String, String>,
) {
    fun tocTitle(path: String): String? = toc[path]

    fun chapterTitle(path: String, doc: Document): String = tocTitle(path) ?: epubChapterTitle(doc, bookTitle)
}

internal fun epubChapterTitle(doc: Document, bookTitle: String? = null): String =
    doc.body().select("h1,h2,h3,h4,h5,h6").firstNotNullOfOrNull { it.epubTitle() }
        ?: doc.head().selectFirst("title")?.epubTitle()?.takeUnless { it == bookTitle }
        ?: "章节"

/** spine 决定阅读顺序，导航文件只为对应的 XHTML 提供目录标题。 */
internal fun readEpubPackage(
    text: (String) -> String?,
    navigationText: (String) -> String?,
    checkCancelled: () -> Unit = {},
): EpubPackage {
    val container = text("META-INF/container.xml") ?: error("EPUB 缺少 container.xml")
    val opfPath = Jsoup.parse(container, "", Parser.xmlParser()).getElementsByTag("rootfile").firstOrNull()?.attr("full-path")
        ?.let { resolveEpubResource("", it) } ?: error("EPUB 未指定内容文件")
    val opf = Jsoup.parse(text(opfPath) ?: error("EPUB 缺少 OPF"), "", Parser.xmlParser())
    val directory = opfPath.substringBeforeLast('/', "")
    val manifest = opf.getElementsByTag("manifest").firstOrNull()?.getElementsByTag("item").orEmpty()
    val paths = manifest.mapNotNull { item ->
        checkCancelled()
        resolveEpubResource(directory, item.attr("href"))?.let { item.attr("id") to it }
    }.toMap()
    val spine = opf.getElementsByTag("spine").firstOrNull()
    val items = spine?.getElementsByTag("itemref").orEmpty().mapNotNull { item ->
        checkCancelled()
        val id = item.attr("idref")
        paths[id]?.let { EpubSpineItem(id, it) }
    }
    val spinePaths = items.mapTo(hashSetOf()) { it.path }
    val toc = mutableMapOf<String, String>()
    fun addTitle(source: String, href: String, label: Element?) {
        checkCancelled()
        val path = resolveEpubResource(source.substringBeforeLast('/', ""), href) ?: return
        if (path in spinePaths && path !in toc) label?.epubTitle()?.let { toc[path] = it }
    }

    val navPath = paths[manifest.firstOrNull { it.hasEpubProperty("nav") }?.attr("id")]
    if (navPath != null) {
        navigationText(navPath)?.let { html ->
            val nav = Jsoup.parse(html).getElementsByTag("nav").firstOrNull { "toc" in it.epubTypes() }
            nav?.select("ol > li > a[href]")?.forEach { addTitle(navPath, it.attr("href"), it) }
        }
    }
    // EPUB 2 目录，以及 EPUB 3 没有可用 NAV 标题时的兼容目录。
    val ncxPath = paths[spine?.attr("toc")] ?: paths[manifest.firstOrNull {
        it.attr("media-type") == "application/x-dtbncx+xml"
    }?.attr("id")]
    if (ncxPath != null && toc.size < spinePaths.size) {
        navigationText(ncxPath)?.let { xml ->
            val ncx = Jsoup.parse(xml, "", Parser.xmlParser())
            ncx.select("navMap navPoint").forEach { point ->
                val target = point.children().firstOrNull { it.normalName() == "content" }
                val label = point.children().firstOrNull { it.normalName() == "navlabel" }?.selectFirst("text")
                if (target != null) addTitle(ncxPath, target.attr("src"), label)
            }
        }
    }
    val metadata = opf.getElementsByTag("metadata").firstOrNull()
    val bookTitle = metadata?.children()?.firstOrNull { it.normalName().substringAfter(':') == "title" }?.epubTitle()
    val coverId = manifest.firstOrNull { it.hasEpubProperty("cover-image") }?.attr("id")
        ?: metadata?.getElementsByTag("meta")?.firstOrNull { it.attr("name") == "cover" }?.attr("content")
    return EpubPackage(items, paths[coverId], bookTitle, toc)
}

private fun Element.hasEpubProperty(property: String) = property in attr("properties").split(propertySeparator)

private fun Element.epubTitle(): String? {
    val label = clone()
    label.select("script,style,rt,rp,rtc").remove()
    return label.text().takeIf { it.isNotBlank() && '\uFFFD' !in it }
}

internal fun epubChapterDocument(html: String): Document = Jsoup.parse(html).apply {
    select("script,style,nav").remove()
}

internal fun resolveEpubResource(directory: String, href: String): String? {
    val reference = href.trim().substringBefore('#')
    val path = try { URLDecoder.decode(reference.replace("+", "%2B"), "UTF-8") }
    catch (_: IllegalArgumentException) { return null }
    if (path.isBlank() || path.startsWith('/') || path.any { it in ":\\?\u0000" }) return null
    return normalizeEpubPath(if (directory.isEmpty()) path else "$directory/$path")
}

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
