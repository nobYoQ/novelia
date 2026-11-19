package cc.novelia.app.files.epub

import cc.novelia.app.data.model.LocalChapter
import cc.novelia.app.files.DocumentTools
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

internal data class EpubContents(val chapters: List<LocalChapter>, val cover: String?)

/**
 * 按 EPUB OPF 的 spine 顺序读取正文；ZIP 随机访问使内存中只需保留当前 XHTML 的解析树。
 * 首先校验条目路径、数量和声明总大小，再在读取时校验实际字节，限制异常压缩包的开销。
 * 图片按哈希交给 imageSink 流式保存，正文只保留 novelia-image 标记；不会请求远程资源。
 * DOM 以块级元素和换行切段；行内样式、注音和书内脚注保存为正文之外的元数据。
 */
internal fun readEpubFile(
    file: File,
    imageSink: ((String, InputStream) -> Unit)?,
    checkCancelled: () -> Unit = {},
    downloadMode: String? = null,
    chapterId: String? = null,
): EpubContents = ZipFile(file).use { zip ->
    val archive = EpubArchive(zip, checkCancelled)
    val book = readEpubPackage(archive::text, archive::navigationText, checkCancelled)
    val imageHashes = mutableMapOf<String, String?>()
    fun image(path: String?): String? {
        if (path == null || imageSink == null) return null
        return imageHashes.getOrPut(path) { archive.image(path, imageSink) }
    }
    val cover = if (chapterId == null) image(book.coverPath) else null
    var textCharacters = 0L
    val notes = EpubNotes(archive::text, checkCancelled)
    val chapters = book.spine.mapNotNull { item ->
        checkCancelled()
        if (chapterId != null && item.id != chapterId) return@mapNotNull null
        val html = archive.text(item.path) ?: return@mapNotNull null
        parseEpubChapter(item.id, html, downloadMode,
            image = { source -> image(resolveEpubResource(item.path.substringBeforeLast('/', ""), source)) },
            checkCancelled = checkCancelled, onText = { paragraph ->
                textCharacters += paragraph.length
                require(textCharacters <= 16 * 1024 * 1024) { "EPUB 正文过长，请先拆分" }
            }, note = { href, explicit -> notes.resolve(item.path, href, explicit) },
            chapterTitle = { book.chapterTitle(item.path, it) })
    }
    EpubContents(chapters, cover)
}

/** 更新旧目录时只匹配原章节 ID，无需重新提取正文、注音或图片。 */
internal fun readEpubTitles(file: File, chapterIds: Set<String>): Map<String, String> {
    DocumentTools.requireImportSize(file.length())
    return ZipFile(file).use { zip ->
        val archive = EpubArchive(zip) {}
        val book = readEpubPackage(archive::text, archive::navigationText)
        buildMap {
            for ((id, path) in book.spine) {
                if (id !in chapterIds) continue
                val title = book.tocTitle(path) ?: archive.text(path)?.let { book.chapterTitle(path, epubChapterDocument(it)) }
                if (title != null) put(id, title)
            }
        }
    }
}

private class EpubArchive(private val zip: ZipFile, private val checkCancelled: () -> Unit) {
    private val entries = linkedMapOf<String, ZipEntry>()

    init {
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
    }

    private fun open(entry: ZipEntry): InputStream = object : FilterInputStream(zip.getInputStream(entry)) {
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
        require(entry.size <= MAX_EPUB_XML_BYTES) { "EPUB 单个正文或目录文件过大，请先拆分" }
        return open(entry).use { decodeEpubXml(DocumentTools.readBounded(it, MAX_EPUB_XML_BYTES, checkCancelled)) }
    }

    fun navigationText(path: String): String? = entries[path]?.takeIf { it.size <= MAX_EPUB_XML_BYTES }?.let { text(path) }

    fun image(path: String, imageSink: (String, InputStream) -> Unit): String? {
        val entry = entries[path] ?: return null
        if (entry.size > 16 * 1024 * 1024) return null
        checkCancelled()
        val hash = open(entry).use { DocumentTools.digest(it, 16 * 1024 * 1024L, checkCancelled) }
        // 第二轮读取将图片流式写入磁盘，文件导入不保留图片 ByteArray 或 Base64。
        open(entry).use { imageSink(hash, it) }
        return hash
    }
}

internal fun normalizeEpubPath(path: String): String? {
    val stack = mutableListOf<String>()
    for (part in path.split('/')) when (part) {
        "", "." -> Unit
        ".." -> if (stack.isEmpty()) return null else stack.removeAt(stack.lastIndex)
        else -> stack += part
    }
    return stack.joinToString("/")
}
