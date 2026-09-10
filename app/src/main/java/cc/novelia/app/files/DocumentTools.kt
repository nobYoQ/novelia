package cc.novelia.app.files

import cc.novelia.app.data.LocalChapter
import cc.novelia.app.data.LocalDocument
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeVisitor
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.zip.ZipInputStream

/** Imports are bounded both compressed and expanded. No archive paths are written to disk. */
object DocumentTools {
    const val MAX_INPUT = 64 * 1024 * 1024
    const val MAX_EXPANDED = 192 * 1024 * 1024
    fun parse(name: String, bytes: ByteArray): LocalDocument {
        require(bytes.size <= MAX_INPUT) { "文件超过 64 MB，请先拆分" }
        val extension = name.substringAfterLast('.', "txt").lowercase()
        val images = linkedMapOf<String, String>(); var cover: String? = null
        val chapters = when(extension) {
            "epub" -> readEpub(bytes, images) { cover = it }
            "txt", "srt" -> parseText(decodeText(bytes), extension)
            else -> error("支持 EPUB、TXT 和 SRT 文件")
        }
        require(chapters.any { it.paragraphs.any(String::isNotBlank) }) { "文件没有可读取的正文" }
        return LocalDocument(UUID.randomUUID().toString(), name.substringBeforeLast('.'), extension, chapters, images = images, coverImage = cover, sourceHash = digest(bytes))
    }
    fun decodeText(bytes: ByteArray): String {
        val charset = when {
            bytes.take(2) == listOf(0xFF.toByte(), 0xFE.toByte()) -> Charsets.UTF_16LE
            bytes.take(2) == listOf(0xFE.toByte(), 0xFF.toByte()) -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return runCatching { charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
            .getOrElse { java.nio.charset.Charset.forName("GB18030").decode(ByteBuffer.wrap(bytes)).toString() }.removePrefix("\uFEFF")
    }
    fun parseText(text: String, format: String = "txt"): List<LocalChapter> {
        if(format == "srt") return listOf(LocalChapter("0", "字幕", text.replace("\r\n", "\n").trim().split(Regex("\n\\s*\n"))))
        val chapters = mutableListOf<LocalChapter>(); var title = "正文"; val paragraphs = mutableListOf<String>()
        fun flush() { if(paragraphs.isNotEmpty()) { chapters += LocalChapter(chapters.size.toString(), title, paragraphs.toList()); paragraphs.clear() } }
        text.lineSequence().forEach { line ->
            val clean = line.trimEnd()
            if(clean.trim().matches(Regex("(?:第.{1,20}[章节卷話话部].{0,55}|[Cc]hapter\\s+\\d+.{0,55}|[序终終]章.{0,40})"))) { flush(); title = clean.trim() }
            else if(clean.isNotBlank()) { paragraphs += clean; if(paragraphs.size >= 800) { flush(); title = "正文 ${chapters.size + 1}" } }
        }
        flush(); return chapters
    }
    fun unzip(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val files = linkedMapOf<String, ByteArray>(); var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while(true) {
                val entry = zip.nextEntry ?: break
                require(files.size < 10000) { "压缩包包含过多文件" }
                val name = normalize(entry.name)
                require(!entry.name.startsWith('/') && !entry.name.contains('\\') && name != null) { "压缩包路径无效" }
                if(!entry.isDirectory) {
                    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while(true) { val count = zip.read(buffer); if(count < 0) break; total += count; require(total <= MAX_EXPANDED) { "解压后文件过大" }; output.write(buffer, 0, count) }
                    files[name!!] = output.toByteArray()
                }
            }
        }
        return files
    }
    private fun normalize(path: String): String? {
        val stack = mutableListOf<String>()
        for(part in path.substringBefore('#').split('/')) when(part) { "", "." -> Unit; ".." -> if(stack.isEmpty()) return null else stack.removeAt(stack.lastIndex); else -> stack += part }
        return stack.joinToString("/")
    }
    fun parseEpub(bytes: ByteArray): List<LocalChapter> = readEpub(bytes, linkedMapOf()) {}
    private fun digest(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun readEpub(bytes: ByteArray, images: MutableMap<String, String>, cover: (String?) -> Unit): List<LocalChapter> {
        val files = unzip(bytes)
        val container = files["META-INF/container.xml"]?.toString(Charsets.UTF_8) ?: error("EPUB 缺少 container.xml")
        val opfPath = Jsoup.parse(container, "", Parser.xmlParser()).getElementsByTag("rootfile").firstOrNull()?.attr("full-path") ?: error("EPUB 未指定内容文件")
        val opf = Jsoup.parse(files[opfPath]?.toString(Charsets.UTF_8) ?: error("EPUB 缺少 OPF"), "", Parser.xmlParser())
        val directory = opfPath.substringBeforeLast('/', "")
        val manifest = opf.getElementsByTag("item").associate { it.attr("id") to it.attr("href") }
        fun resource(base: String, href: String): String? {
            if(href.contains(':') || href.startsWith('/')) return null
            val decoded = java.net.URLDecoder.decode(href.replace("+", "%2B"), "UTF-8")
            return normalize(if(base.isBlank()) decoded else "$base/$decoded")
        }
        fun image(path: String?): String? {
            val content = files[path] ?: return null
            if(content.size > 16 * 1024 * 1024) return null
            val hash = digest(content); images[hash] = java.util.Base64.getEncoder().encodeToString(content); return hash
        }
        val coverId = opf.getElementsByTag("meta").firstOrNull { it.attr("name") == "cover" }?.attr("content")
        val coverHref = opf.getElementsByTag("item").firstOrNull { it.attr("properties").split(' ').contains("cover-image") }?.attr("href") ?: manifest[coverId]
        cover(coverHref?.let { image(resource(directory, it)) })
        return opf.getElementsByTag("itemref").mapNotNull { item ->
            val href = manifest[item.attr("idref")] ?: return@mapNotNull null
            val decoded = java.net.URLDecoder.decode(href.replace("+", "%2B"), "UTF-8")
            val path = normalize(if(directory.isBlank()) decoded else "$directory/$decoded") ?: return@mapNotNull null
            val html = files[path]?.toString(Charsets.UTF_8) ?: return@mapNotNull null
            val doc = Jsoup.parse(html)
            doc.select("script,style,nav,rt,rp").remove()
            val paragraphs = mutableListOf<String>(); val buffer = StringBuilder(); val blocks = setOf("h1", "h2", "h3", "h4", "p", "li", "blockquote", "pre", "div", "section", "br")
            fun flush() { val text = buffer.toString().trim(); if(text.isNotEmpty()) paragraphs += text; buffer.clear() }
            doc.body().traverse(object : NodeVisitor {
                override fun head(node: Node, depth: Int) {
                    when(node) {
                        is TextNode -> if(!node.isBlank || buffer.isNotEmpty()) buffer.append(node.wholeText)
                        is Element -> {
                            if(node.tagName() in blocks) flush()
                            if(node.tagName() in setOf("img", "image")) {
                                flush(); val source = node.attr("src").ifBlank { node.attr("xlink:href").ifBlank { node.attr("href") } }
                                image(resource(path.substringBeforeLast('/', ""), source))?.let { paragraphs += "novelia-image:$it" }
                            }
                        }
                    }
                }
                override fun tail(node: Node, depth: Int) { if(node is Element && node.tagName() in blocks) flush() }
            }); flush()
            val title = doc.selectFirst("h1,h2,h3,title")?.text()?.takeIf(String::isNotBlank) ?: "章节"
            if(paragraphs.isEmpty()) null else LocalChapter(item.attr("idref"), title, paragraphs)
        }
    }
    fun epubToTxt(bytes: ByteArray) = parseEpub(bytes).joinToString("\n\n") { it.title + "\n\n" + it.paragraphs.filterNot { p -> p.startsWith("novelia-image:") }.joinToString("\n\n") }
    fun repairOcr(text: String): String = text.replace("\r\n", "\n").split(Regex("\n[\\t ]*\n")).joinToString("\n\n") { block ->
        block.lines().map(String::trim).filter(String::isNotBlank).fold("") { acc, line ->
            if(acc.isEmpty()) line else if(acc.last() in "。！？!?」』：:" || line.startsWith('「') || line.startsWith('『')) "$acc\n$line" else acc + line
        }
    }
    fun katakana(text: String): List<Pair<String, Int>> = Regex("[ァ-ヶー・]{2,}").findAll(text).map { it.value.trim('・', 'ー') }.filter { it.length >= 2 }.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }
}
