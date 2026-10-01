package cc.novelia.app.files

import cc.novelia.app.data.model.LocalChapter
import cc.novelia.app.data.model.LocalDocument
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.zip.ZipInputStream
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.NodeVisitor

/**
 * EPUB、TXT、SRT 的解析与转换工具，统一限制输入大小和压缩内容展开体积。
 * 文件导入优先使用 parseFile 的流式图片出口；parse(ByteArray) 适合已在内存中的小文件。
 * 归档内部路径只用于解析引用，落盘路径由调用方根据文档 ID 和内容哈希生成。
 */
object DocumentTools {
    const val MAX_INPUT = 64 * 1024 * 1024
    const val MAX_EXPANDED = 192 * 1024 * 1024
    fun requireImportSize(size: Long) { require(size in 0..MAX_INPUT.toLong()) { "文件超过 64 MB，请先拆分；也可导出后使用其他阅读器打开" } }
    fun readBounded(input: InputStream, limit: Int = MAX_INPUT, checkCancelled: () -> Unit = {}): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            checkCancelled()
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= limit) { "文件内容超过允许的大小" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    fun digest(input: InputStream, limit: Long = MAX_INPUT.toLong(), checkCancelled: () -> Unit = {}): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        var total = 0L
        while (true) {
            checkCancelled()
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "文件内容超过允许的大小" }
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun parseFile(name: String, file: File, imageSink: (String, InputStream) -> Unit, sourceHash: String? = null, checkCancelled: () -> Unit = {}): LocalDocument {
        requireImportSize(file.length())
        val extension = name.substringAfterLast('.', "txt").lowercase()
        val contents = when (extension) {
            "epub" -> readEpubFile(file, imageSink, checkCancelled)
            "txt", "srt" -> {
                val text = file.inputStream().use { decodeText(readBounded(it, checkCancelled = checkCancelled)) }
                checkCancelled()
                require(text.length <= 16 * 1024 * 1024) { "正文过长，请先拆分" }
                EpubContents(parseText(text, extension, checkCancelled), null)
            }
            else -> error("支持 EPUB、TXT 和 SRT 文件")
        }
        require(contents.chapters.any { it.paragraphs.any(String::isNotBlank) }) { "文件没有可读取的正文" }
        checkCancelled()
        return LocalDocument(UUID.randomUUID().toString(), name.substringBeforeLast('.'), extension, contents.chapters,
            coverImage = contents.cover, sourceHash = sourceHash ?: file.inputStream().use { digest(it, checkCancelled = checkCancelled) })
    }
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
    /** 优先按 BOM 判断 UTF-16，否则严格尝试 UTF-8；解码失败再回退 GB18030，最后移除 BOM。 */
    fun decodeText(bytes: ByteArray): String {
        val charset = when {
            bytes.take(2) == listOf(0xFF.toByte(), 0xFE.toByte()) -> Charsets.UTF_16LE
            bytes.take(2) == listOf(0xFE.toByte(), 0xFF.toByte()) -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return runCatching { charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
            .getOrElse { java.nio.charset.Charset.forName("GB18030").decode(ByteBuffer.wrap(bytes)).toString() }.removePrefix("\uFEFF")
    }
    /** TXT 按常见章节标题切分，无标题长文每 800 段分章；SRT 保留字幕块内容作为单章段落。 */
    fun parseText(text: String, format: String = "txt", checkCancelled: () -> Unit = {}): List<LocalChapter> {
        checkCancelled()
        if(format == "srt") {
            val normalized = text.replace("\r\n", "\n").trim()
            val parts = mutableListOf<String>()
            var start = 0
            for (separator in Regex("\n\\s*\n").findAll(normalized)) {
                checkCancelled()
                parts += normalized.substring(start, separator.range.first)
                start = separator.range.last + 1
            }
            checkCancelled()
            parts += normalized.substring(start)
            return listOf(LocalChapter("0", "字幕", parts))
        }
        val chapters = mutableListOf<LocalChapter>(); var title = "正文"; val paragraphs = mutableListOf<String>()
        val chapterHeading = Regex("(?:第.{1,20}[章节卷話话部].{0,55}|[Cc]hapter\\s+\\d+.{0,55}|[序终終]章.{0,40})")
        fun flush() { if(paragraphs.isNotEmpty()) { chapters += LocalChapter(chapters.size.toString(), title, paragraphs.toList()); paragraphs.clear() } }
        text.lineSequence().forEach { line ->
            checkCancelled()
            val clean = line.trimEnd()
            if(clean.trim().matches(chapterHeading)) { flush(); title = clean.trim() }
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
    fun repairOcr(text: String, checkCancelled: () -> Unit = {}): String = buildString(text.length) {
        // 保留原来的段落和分隔符规则，同时避免反复复制已累积的文本块。
        var cursor = 0
        val normalized = text.replace("\r\n", "\n")
        val boundaries = Regex("\n[\\t ]*\n").findAll(normalized).iterator()
        fun appendBlock(block: String) {
            var first = true
            for (raw in block.lineSequence()) {
                checkCancelled()
                val line = raw.trim()
                if (line.isEmpty()) continue
                if (!first && (last() in "。！？!?」』：:" || line.startsWith('「') || line.startsWith('『'))) append('\n')
                append(line)
                first = false
            }
        }
        for (boundary in boundaries) {
            appendBlock(normalized.substring(cursor, boundary.range.first))
            append("\n\n")
            cursor = boundary.range.last + 1
        }
        appendBlock(normalized.substring(cursor))
    }
    fun katakana(text: String): List<Pair<String, Int>> = Regex("[ァ-ヶー・]{2,}").findAll(text).map { it.value.trim('・', 'ー') }.filter { it.length >= 2 }.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }
}
