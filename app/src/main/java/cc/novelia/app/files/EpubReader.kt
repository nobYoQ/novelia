package cc.novelia.app.files

import cc.novelia.app.data.model.LocalChapter
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.NodeVisitor

internal data class EpubContents(val chapters: List<LocalChapter>, val cover: String?)

/**
 * 按 EPUB OPF 的 spine 顺序读取正文；ZIP 随机访问使内存中只需保留当前 XHTML 的解析树。
 * 首先校验条目路径、数量和声明总大小，再在读取时校验实际字节，限制异常压缩包的开销。
 * 图片按哈希交给 imageSink 流式保存，正文只保留 novelia-image 标记；不会请求远程资源。
 * DOM 以块级元素和换行切段，删除脚本、样式、导航及注音节点，保留正文中的插图顺序。
 */
internal fun readEpubFile(
    file: File,
    imageSink: ((String, InputStream) -> Unit)?,
    checkCancelled: () -> Unit = {},
): EpubContents = ZipFile(file).use { zip ->
    val entries = linkedMapOf<String, ZipEntry>()
    var expanded = 0L
    val enumeration = zip.entries()
    var entryCount = 0
    while (enumeration.hasMoreElements()) {
        checkCancelled()
        val entry = enumeration.nextElement()
        require(++entryCount <= 10_000) { "压缩包包含过多文件" }
        val path = normalizeEpubPath(entry.name)
        require(!entry.name.startsWith('/') && !entry.name.contains('\\') && path != null) { "压缩包路径无效" }
        if (!entry.isDirectory) {
            require(entry.size >= 0) { "压缩包文件大小无效" }
            expanded += entry.size
            require(expanded <= DocumentTools.MAX_EXPANDED) { "解压后文件过大" }
            require(entries.put(path!!, entry) == null) { "压缩包包含重复路径" }
        }
    }
    fun open(entry: ZipEntry): InputStream = object : FilterInputStream(zip.getInputStream(entry)) {
        private var consumed = 0L
        private fun verify(count: Int): Int {
            checkCancelled()
            if (count < 0) require(consumed == entry.size) { "压缩包条目不完整" }
            else {
                consumed += count
                // 前面已检查条目声明大小之和，实际读取字节也必须满足同一预算。
                require(consumed <= entry.size) { "压缩包条目大小与内容不符" }
            }
            return count
        }
        override fun read(): Int {
            val value = `in`.read()
            verify(if (value < 0) -1 else 1)
            return value
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = verify(`in`.read(buffer, offset, length))
    }
    fun text(path: String): String? {
        val entry = entries[path] ?: return null
        require(entry.size <= 8 * 1024 * 1024) { "EPUB 单个正文或目录文件过大，请先拆分" }
        return open(entry).use { DocumentTools.readBounded(it, 8 * 1024 * 1024, checkCancelled).toString(Charsets.UTF_8) }
    }
    fun resource(base: String, href: String): String? {
        if (href.contains(':') || href.startsWith('/')) return null
        val decoded = java.net.URLDecoder.decode(href.replace("+", "%2B"), "UTF-8")
        if (decoded.contains('\\') || decoded.startsWith('/')) return null
        return normalizeEpubPath(if (base.isBlank()) decoded else "$base/$decoded")
    }
    val imageHashes = mutableMapOf<String, String?>()
    fun image(path: String?): String? {
        if (path == null || imageSink == null) return null
        if (imageHashes.containsKey(path)) return imageHashes[path]
        val entry = entries[path] ?: return null
        if (entry.size > 16 * 1024 * 1024) return null
        checkCancelled()
        val hash = open(entry).use { DocumentTools.digest(it, 16 * 1024 * 1024L, checkCancelled) }
        // 第二轮读取将图片流式写入磁盘，文件导入不保留图片 ByteArray 或 Base64。
        open(entry).use { imageSink(hash, it) }
        imageHashes[path] = hash
        return hash
    }
    val container = text("META-INF/container.xml") ?: error("EPUB 缺少 container.xml")
    val opfPath = Jsoup.parse(container, "", Parser.xmlParser()).getElementsByTag("rootfile").firstOrNull()?.attr("full-path")
        ?.let(::normalizeEpubPath) ?: error("EPUB 未指定内容文件")
    val opf = Jsoup.parse(text(opfPath) ?: error("EPUB 缺少 OPF"), "", Parser.xmlParser())
    val directory = opfPath.substringBeforeLast('/', "")
    val manifest = opf.getElementsByTag("item").associate { it.attr("id") to it.attr("href") }
    val coverId = opf.getElementsByTag("meta").firstOrNull { it.attr("name") == "cover" }?.attr("content")
    val coverHref = opf.getElementsByTag("item").firstOrNull { it.attr("properties").split(' ').contains("cover-image") }?.attr("href") ?: manifest[coverId]
    val cover = coverHref?.let { image(resource(directory, it)) }
    var textCharacters = 0L
    val chapters = opf.getElementsByTag("itemref").mapNotNull { item ->
        checkCancelled()
        val href = manifest[item.attr("idref")] ?: return@mapNotNull null
        val path = resource(directory, href) ?: return@mapNotNull null
        val html = text(path) ?: return@mapNotNull null
        val doc = Jsoup.parse(html)
        doc.select("script,style,nav,rt,rp").remove()
        val paragraphs = mutableListOf<String>()
        val buffer = StringBuilder()
        val blocks = setOf("h1", "h2", "h3", "h4", "p", "li", "blockquote", "pre", "div", "section", "br")
        fun flush() {
            val paragraph = buffer.toString().trim()
            if (paragraph.isNotEmpty()) {
                textCharacters += paragraph.length
                require(textCharacters <= 16 * 1024 * 1024) { "EPUB 正文过长，请先拆分" }
                paragraphs += paragraph
            }
            buffer.clear()
        }
        doc.body().traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                checkCancelled()
                when (node) {
                    is TextNode -> if (!node.isBlank || buffer.isNotEmpty()) buffer.append(node.wholeText)
                    is Element -> {
                        if (node.tagName() in blocks) flush()
                        if (node.tagName() in setOf("img", "image")) {
                            flush()
                            val source = node.attr("src").ifBlank { node.attr("xlink:href").ifBlank { node.attr("href") } }
                            image(resource(path.substringBeforeLast('/', ""), source))?.let { paragraphs += "novelia-image:$it" }
                        }
                    }
                }
            }
            override fun tail(node: Node, depth: Int) { if (node is Element && node.tagName() in blocks) flush() }
        })
        flush()
        val title = doc.selectFirst("h1,h2,h3,title")?.text()?.takeIf(String::isNotBlank) ?: "章节"
        if (paragraphs.isEmpty()) null else LocalChapter(item.attr("idref"), title, paragraphs)
    }
    EpubContents(chapters, cover)
}

internal fun normalizeEpubPath(path: String): String? {
    val stack = mutableListOf<String>()
    for (part in path.substringBefore('#').split('/')) when (part) {
        "", "." -> Unit
        ".." -> if (stack.isEmpty()) return null else stack.removeAt(stack.lastIndex)
        else -> stack += part
    }
    return stack.joinToString("/")
}
