package cc.novelia.app.files.comic

import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import cc.novelia.app.data.model.*
import cc.novelia.app.data.storage.hashName
import cc.novelia.app.files.DocumentTools
import com.ibm.icu.text.Collator
import com.ibm.icu.text.RuleBasedCollator
import com.ibm.icu.util.ULocale
import java.io.*
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipFile
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

internal const val comicInputLimit = 1024L * 1024 * 1024
internal const val comicExpandedLimit = 4 * comicInputLimit
internal const val comicPageLimit = 64 * 1024 * 1024
internal const val seriesMarker = "META-INF/novelia-series"
internal fun comicOrder(): Comparator<String> {
    val collator = (Collator.getInstance(ULocale.ROOT) as RuleBasedCollator).apply { numericCollation = true }
    return compareBy(collator) { path: String -> path }.thenBy { it }
}

internal fun transferComic(input: InputStream, output: OutputStream, limit: Long, check: () -> Unit): Long {
    val buffer = ByteArray(65536)
    var total = 0L
    while (true) {
        check()
        val n = input.read(buffer)
        if (n < 0) return total
        total += n
        require(total <= limit) { "漫画文件超过大小限制" }
        output.write(buffer, 0, n)
    }
}

private fun validPath(name: String): Boolean {
    val path = name.replace('\\', '/')
    val parts = path.split('/')
    require(path.length <= 4096 && !path.startsWith('/') && !parts.first().endsWith(':') && ".." !in parts) { "压缩包路径无效" }
    return parts.none { it.startsWith('.') || it == "__MACOSX" }
}

// visit returns true once the requested entry is found. Native solid archives remain sequential.
internal fun visitComicArchive(file: File, format: String, check: () -> Unit,
    visit: (String, Long, () -> InputStream) -> Boolean) {
    var count = 0
    var expanded = 0L
    fun accept(name: String, size: Long, regular: Boolean): Boolean {
        check()
        require(++count <= 10000 && size in 0..(comicExpandedLimit - expanded)) { "漫画归档过大或条目过多" }
        expanded += size
        return validPath(name) && regular
    }
    if (format in setOf("zip", "cbz")) {
        ZipFile(file).use { zip ->
            for (entry in zip.entries()) {
                if (accept(entry.name, entry.size, !entry.isDirectory) && visit(entry.name, entry.size) { zip.getInputStream(entry) }) break
            }
        }
        return
    }
    val archive = Archive.readNew()
    try {
        when (format) {
            "rar", "cbr" -> { Archive.readSupportFormatRar(archive); Archive.readSupportFormatRar5(archive) }
            "7z", "cb7" -> Archive.readSupportFormat7zip(archive)
            "tar", "cbt" -> Archive.readSupportFormatTar(archive)
            else -> error("不支持的漫画归档")
        }
        Archive.readOpenFileName(archive, file.absolutePath.toByteArray(Charsets.UTF_8), 65536)
        val block = ByteBuffer.allocateDirect(65536)
        while (true) {
            check()
            val entry = Archive.readNextHeader(archive)
            if (entry == 0L) break
            require(!ArchiveEntry.isEncrypted(entry)) { "请先解密漫画压缩包" }
            val name = ArchiveEntry.pathnameUtf8(entry) ?: error("漫画文件名无法读取")
            val regular = ArchiveEntry.filetype(entry) == ArchiveEntry.AE_IFREG && !ArchiveEntry.hardlinkIsSet(entry)
            if (!accept(name, ArchiveEntry.size(entry), regular)) continue
            if (visit(name, ArchiveEntry.size(entry)) {
                object : InputStream() {
                    override fun read(): Int { val b = ByteArray(1); return if (read(b) < 0) -1 else b[0].toInt() and 255 }
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (len == 0) return 0
                        check()
                        block.clear(); block.limit(minOf(len, block.capacity()))
                        Archive.readData(archive, block)
                        val n = block.position()
                        block.flip(); block.get(b, off, n)
                        return if (n == 0) -1 else n
                    }
                }
            }) break
        }
    } finally { Archive.free(archive) }
}

private fun imageHeader(name: String, input: InputStream, size: Long): ArchivePage {
    require(size in 1..comicPageLimit.toLong()) { "漫画图片为空或超过 64 MiB" }
    // Bound header I/O independently of image resolution and archive compression.
    val prefix = ByteArray(minOf(size, 256 * 1024L).toInt())
    val data = DataInputStream(input)
    data.readFully(prefix)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(prefix, 0, prefix.size, bounds)
    val orientation = if (bounds.outMimeType == "image/jpeg") try {
        ExifInterface(prefix.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
    } catch (_: IOException) { 1 } else 1
    val swap = orientation in 5..8
    val w = if (swap) bounds.outHeight else bounds.outWidth
    val h = if (swap) bounds.outWidth else bounds.outHeight
    val valid = w > 0 && h > 0 && h.toLong() <= w.toLong() * 512
    return ArchivePage(name, if (valid) w else 3, if (valid) h else 4, orientation,
        bounds.outMimeType in setOf("image/jpeg","image/png","image/webp"))
}

internal fun extractComicPage(file: File, format: String, name: String, output: OutputStream, check: () -> Unit) {
    fun copy(input: InputStream, size: Long) {
        require(size in 1..comicPageLimit.toLong()) { "漫画单页过大" }
        input.use { require(transferComic(it, output, size, check) == size) { "漫画页不完整" } }
    }
    if (format in setOf("zip","cbz")) {
        ZipFile(file).use { zip ->
            val entry = requireNotNull(zip.getEntry(name)) { "漫画页已丢失" }
            val stream = java.util.zip.CheckedInputStream(zip.getInputStream(entry), java.util.zip.CRC32())
            copy(stream, entry.size)
            require(stream.checksum.value == entry.crc) { "漫画页校验失败" }
        }
    } else {
        var found = false
        visitComicArchive(file, format, check) { path, size, open ->
            if (path == name) { copy(open(), size); found = true }
            found
        }
        require(found) { "漫画页已丢失" }
    }
}

internal fun indexComic(file: File, name: String, sourceHash: String, stage: File, check: () -> Unit): LocalDocument {
    val format = name.substringAfterLast('.').lowercase()
    val entries = mutableMapOf<String, ArchivePage>()
    var meta: ComicMetadata? = null
    var front: Int? = null
    var series = false
    visitComicArchive(file, format, check) { path, size, open ->
        when {
            path == seriesMarker -> series = true
            path.substringAfterLast('/').equals("ComicInfo.xml", true) && meta == null && size in 1..262144 -> {
                val xml = open().use { val bytes = ByteArray(size.toInt()); DataInputStream(it).readFully(bytes); Jsoup.parse(bytes.toString(Charsets.UTF_8), "", Parser.xmlParser()) }
                fun field(tag: String) = xml.selectFirst("ComicInfo > " + tag)?.text().orEmpty().take(512)
                meta = ComicMetadata(field("Series"), field("Title"), field("Number"), field("Writer"))
                front = xml.select("Page").firstOrNull { it.attr("Type").equals("FrontCover", true) }?.attr("Image")?.toIntOrNull()
            }
            isComicImage(path) -> {
                require(path !in entries) { "漫画包含重名页面" }
                entries[path] = open().use { imageHeader(path, it, size) }
            }
        }
        false
    }
    val sorted = entries.keys.sortedWith(comicOrder())
    require(sorted.isNotEmpty()) { "压缩包中没有可读取的漫画图片" }
    val dedicatedCover = sorted.firstOrNull { it.equals("cover.jpg", true) || it.equals("cover.png", true) }
    val coverName = dedicatedCover ?: front?.let(sorted::getOrNull) ?: sorted.first()
    val pages = sorted.filter { (!series || '/' in it) && (it != dedicatedCover || sorted.size == 1) }
    require(pages.isNotEmpty()) { "系列中没有章节" }
    val cover = File.createTempFile("cover-", ".tmp", stage)
    cover.outputStream().use { extractComicPage(file, format, coverName, it, check) }
    val coverHash = cover.inputStream().use { DocumentTools.digest(it, comicPageLimit.toLong(), check) }
    Files.move(cover.toPath(), File(stage,coverHash).toPath(), StandardCopyOption.REPLACE_EXISTING)
    val groups = pages.indices.groupBy { if (series) pages[it].substringBefore('/') else "全册" }
    val chapters = groups.map { (title, indexes) -> LocalChapter(if (series) hashName(title) else "0", title,
        indexes.map { "novelia-archive:" + it }) }
    val fallback = name.substringBeforeLast('.')
    return LocalDocument(UUID.randomUUID().toString(), meta?.label(fallback) ?: fallback, format, chapters,
        sourceHash = sourceHash, coverImage = coverHash, archivePages = pages.map(entries::getValue), comicSeries = series, comicMetadata = meta)
}
