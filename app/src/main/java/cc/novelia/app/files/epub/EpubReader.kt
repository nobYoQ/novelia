package cc.novelia.app.files.epub

import cc.novelia.app.data.model.LocalChapter
import cc.novelia.app.data.model.EpubNavigationEntry
import cc.novelia.app.files.DocumentTools
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

internal data class EpubContents(
    val chapters: List<LocalChapter>,
    val cover: String?,
    val navigation: List<EpubNavigationEntry> = emptyList(),
    val authors: List<String> = emptyList(),
)

internal data class EpubDirectory(val titles: Map<String, String>, val navigation: List<EpubNavigationEntry>)

internal fun List<EpubNavigationEntry>.chapterIds() = mapNotNull { it.target?.chapterId }.distinct()

private const val UNAVAILABLE_CHAPTER = "无法显示此 EPUB 章节"

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
    preserveLegacyBody: Boolean = false,
): EpubContents = ZipFile(file).use { zip ->
    val archive = EpubArchive(zip, checkCancelled)
    val book = readEpubPackage(archive::text, archive::navigationText, archive::contains, checkCancelled)
    val imageHashes = mutableMapOf<String, String?>()
    fun image(path: String?): String? {
        if (path == null || imageSink == null) return null
        return imageHashes.getOrPut(path) { archive.image(path, imageSink) }
    }
    readEpubContents(book, archive::text, ::image, EpubNotes(archive::text, checkCancelled), downloadMode, checkCancelled, chapterId, preserveLegacyBody)
}

internal fun readEpubContents(
    book: EpubPackage,
    text: (String) -> String?,
    image: (String?) -> String?,
    notes: EpubNotes,
    downloadMode: String?,
    checkCancelled: () -> Unit = {},
    chapterId: String? = null,
    preserveLegacyBody: Boolean = false,
): EpubContents {
    val cover = if (chapterId == null) image(book.coverPath) else null
    var textCharacters = 0L
    val chapters = book.spine.mapNotNull { item ->
        checkCancelled()
        if (chapterId != null && item.id != chapterId) return@mapNotNull null
        val path = item.contentPath
        val html = path?.let(text)
        if (path == null || html == null) {
            return@mapNotNull LocalChapter(item.id, book.tocTitle(item) ?: UNAVAILABLE_CHAPTER,
                listOf("此章节使用当前阅读器无法打开的资源，且没有可读取的替代内容。"),
                epubContentVersion = EPUB_CONTENT_VERSION, downloadMode = downloadMode)
        }
        val directory = path.substringBeforeLast('/', "")
        parseEpubChapter(item.id, html, downloadMode,
            image = { source -> image(resolveEpubResource(directory, source)) },
            checkCancelled = checkCancelled, onText = { paragraph ->
                textCharacters += paragraph.length
                require(textCharacters <= 16 * 1024 * 1024) { "EPUB 正文过长，请先拆分" }
            }, note = { href, explicit -> notes.resolve(path, href, explicit) },
            chapterTitle = { book.chapterTitle(item, it) }, link = { book.linkTarget(path, it, item.sourcePath ?: path) },
            preserveLegacyBody = preserveLegacyBody)
    }
    return EpubContents(chapters, cover, book.navigation(chapters.mapTo(hashSetOf()) { it.id }), book.authors)
}

/** 补回旧导入的作者时只读容器和 OPF。 */
internal fun readEpubAuthors(file: File, checkCancelled: () -> Unit): List<String> {
    DocumentTools.requireImportSize(file.length())
    return ZipFile(file).use { zip ->
        val archive = EpubArchive(zip, checkCancelled)
        readEpubOpf(archive::text).second.epubPackageAuthors()
    }
}

/** 更新旧目录时只匹配原章节 ID，无需重新提取正文、注音或图片。 */
internal fun readEpubDirectory(file: File, chapterIds: Set<String>): EpubDirectory {
    DocumentTools.requireImportSize(file.length())
    return ZipFile(file).use { zip ->
        val archive = EpubArchive(zip) {}
        val book = readEpubPackage(archive::text, archive::navigationText, archive::contains)
        val titles = buildMap {
            for (item in book.spine) {
                if (item.id !in chapterIds) continue
                val title = book.tocTitle(item) ?: item.contentPath?.let(archive::text)?.let {
                    book.chapterTitle(item, epubChapterDocument(it))
                }
                put(item.id, title ?: UNAVAILABLE_CHAPTER)
            }
        }
        EpubDirectory(titles, book.navigation(titles.keys))
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

    fun contains(path: String): Boolean = path in entries

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
