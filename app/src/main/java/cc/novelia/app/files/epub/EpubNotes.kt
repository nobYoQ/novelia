package cc.novelia.app.files.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** 书内引用按 XHTML 路径 + fragment 区分；可读取不在 spine 中的注释文件。 */
internal class EpubNotes(private val text: (String) -> String?, private val checkCancelled: () -> Unit = {}) {
    private val documents = linkedMapOf<String, Document>()
    private var parsedCharacters = 0L

    fun resolve(source: String, href: String, explicit: Boolean): Pair<String, String>? {
        checkCancelled()
        if ('#' !in href || href.startsWith('/') || href.contains(':') || href.contains('\\')) return null
        fun decode(value: String) = runCatching { java.net.URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrNull()
        val relative = decode(href.substringBefore('#')) ?: return null
        val fragment = decode(href.substringAfter('#'))?.takeIf { it.isNotBlank() } ?: return null
        if (relative.contains(':') || relative.startsWith('/') || relative.contains('\\') || '?' in relative) return null
        val path = if (relative.isEmpty()) source else normalizeEpubPath(
            source.substringBeforeLast('/', "").let { if (it.isEmpty()) relative else "$it/$relative" }) ?: return null
        val doc = documents[path] ?: run {
            val html = text(path) ?: return null
            // 限制大量不同锚点/资源造成的重复解析开销，失败时正文链接仍保留。
            if (parsedCharacters + html.length > 16 * 1024 * 1024) return null
            parsedCharacters += html.length
            checkCancelled()
            Jsoup.parse(html).also {
                if (documents.size == 4) documents.remove(documents.keys.first())
                documents[path] = it
            }
        }
        val target = doc.getElementById(fragment) ?: return null
        if (!explicit && !target.isEpubNote()) return null
        val note = target.clone()
        note.select("script,style,nav,rt,rp").remove()
        note.select("a").filter { "backlink" in it.epubTypes() || it.attr("role") == "doc-backlink" }.forEach { it.remove() }
        val body = normalizeEpubWhitespace(note.text(), checkCancelled)
        if (body.isBlank() || body.length > 64 * 1024) return null
        return "$path#$fragment" to body
    }
}
