package cc.novelia.app.files.comic

import cc.novelia.app.data.model.LocalChapter
import cc.novelia.app.files.DocumentTools
import com.ibm.icu.text.Collator
import com.ibm.icu.text.RuleBasedCollator
import com.ibm.icu.util.ULocale
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.zip.ZipInputStream
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException

internal const val maxComicPageBytes = 16 * 1024 * 1024
private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
internal fun isComicImage(name: String) = name.substringAfterLast('.', "").lowercase() in imageExtensions
internal fun isRasterSpineImage(path: String, mediaType: String) =
    if (mediaType.isBlank()) isComicImage(path)
    else mediaType in setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp")

/** 文件名只参与排序，归档中的链接不跟随，图片只按内容哈希安装。 */
internal class ComicArchive(private val sink: (String, InputStream) -> Unit, private val checkCancelled: () -> Unit) {
    private val pages = mutableMapOf<String, String>()
    private val installed = mutableSetOf<String>()
    private val buffer = ByteArray(32 * 1024)
    private var entries = 0
    private var expanded = 0L

    fun read(file: File, format: String): LocalChapter {
        if (format == "zip" || format == "cbz") return file.inputStream().use(::readZip)
        return readNative(format) { Archive.readOpenFileName(it, file.absolutePath.toByteArray(Charsets.UTF_8), 32_768) }
    }

    fun read(bytes: ByteArray, format: String): LocalChapter {
        if (format == "zip" || format == "cbz") return readZip(bytes.inputStream())
        // 原生库会跨 JNI 调用持有输入。两个入口都使用可寻址的文件，避免持有 Java 堆数组。
        val staged = File.createTempFile("comic-bytes-", ".tmp")
        return try {
            staged.writeBytes(bytes)
            read(staged, format)
        } finally { staged.delete() }
    }

    private fun readZip(input: InputStream): LocalChapter {
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                checkCancelled()
                val entry = zip.nextEntry ?: break
                readEntry(entry.name, !entry.isDirectory) { zip.read(buffer) }
            }
        }
        return chapter()
    }

    private fun readNative(format: String, open: (Long) -> Unit): LocalChapter {
        val archive = Archive.readNew()
        try {
            when (format) {
                "rar", "cbr" -> { Archive.readSupportFormatRar(archive); Archive.readSupportFormatRar5(archive) }
                "7z", "cb7" -> Archive.readSupportFormat7zip(archive)
                "tar", "cbt" -> Archive.readSupportFormatTar(archive)
                else -> error("未知漫画归档格式")
            }
            open(archive)
            val block = ByteBuffer.allocateDirect(buffer.size)
            while (true) {
                checkCancelled()
                val entry = Archive.readNextHeader(archive)
                if (entry == 0L) break
                require(!ArchiveEntry.isEncrypted(entry)) { "请先解密漫画压缩包再导入" }
                val name = ArchiveEntry.pathnameUtf8(entry) ?: throw IllegalArgumentException("漫画文件名无法读取")
                if (ArchiveEntry.sizeIsSet(entry)) require(ArchiveEntry.size(entry) in 0L..(DocumentTools.MAX_EXPANDED - expanded)) { "解压后文件过大" }
                val regular = ArchiveEntry.filetype(entry) == ArchiveEntry.AE_IFREG && !ArchiveEntry.hardlinkIsSet(entry)
                readEntry(name, regular) {
                    block.clear()
                    Archive.readData(archive, block)
                    val size = block.position()
                    block.flip()
                    block.get(buffer, 0, size)
                    if (size == 0) -1 else size
                }
            }
        } catch (e: ArchiveException) {
            throw IllegalArgumentException("无法读取漫画压缩包，请检查格式、密码或分卷是否完整", e)
        } finally { Archive.free(archive) }
        return chapter()
    }

    private fun readEntry(name: String, regular: Boolean, read: () -> Int) {
        require(++entries <= 10_000) { "压缩包包含过多文件" }
        val normalized = name.replace('\\', '/')
        val parts = normalized.split('/')
        require(!normalized.startsWith('/') && !parts.first().endsWith(':') && ".." !in parts) { "压缩包路径无效" }
        val path = parts.filter { it.isNotEmpty() && it != "." }
        val output = if (regular && isComicImage(name) && path.none { it.startsWith('.') || it == "__MACOSX" }) ByteArrayOutputStream() else null
        while (true) {
            checkCancelled()
            val count = read()
            if (count < 0) break
            expanded += count
            require(expanded <= DocumentTools.MAX_EXPANDED) { "解压后文件过大" }
            if (output != null) {
                require(output.size() + count <= maxComicPageBytes) { "漫画单页超过 16 MB，请先缩小图片" }
                output.write(buffer, 0, count)
            }
        }
        if (output == null) return
        val bytes = output.toByteArray()
        require(bytes.isNotEmpty()) { "漫画包含空白图片文件" }
        val hash = DocumentTools.digest(bytes.inputStream(), checkCancelled = checkCancelled)
        require(pages.put(path.joinToString("/"), hash) == null) { "漫画包含重名页面" }
        if (installed.add(hash)) bytes.inputStream().use { sink(hash, it) }
    }

    private fun chapter(): LocalChapter {
        require(pages.isNotEmpty()) { "压缩包中没有可读取的漫画图片" }
        val collator = (Collator.getInstance(ULocale.ROOT) as RuleBasedCollator).apply { numericCollation = true }
        val ordered = pages.keys.sortedWith(compareBy(collator) { path: String -> path }.thenBy { it })
        return LocalChapter("0", "全册", ordered.map { "novelia-image:${pages.getValue(it)}" })
    }
}
