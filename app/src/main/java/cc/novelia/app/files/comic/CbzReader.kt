package cc.novelia.app.files.comic

import cc.novelia.app.data.model.LocalChapter
import cc.novelia.app.files.DocumentTools
import com.ibm.icu.text.Collator
import com.ibm.icu.text.RuleBasedCollator
import com.ibm.icu.util.ULocale
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** 一册 CBZ 对应一章。文件名仅用于排序，图片落盘一律使用内容哈希。 */
internal fun readCbz(input: InputStream, imageSink: (String, InputStream) -> Unit, checkCancelled: () -> Unit): LocalChapter {
    val pages = mutableMapOf<String, String>()
    val installed = mutableSetOf<String>()
    val buffer = ByteArray(32 * 1024)
    var entries = 0
    var expanded = 0L
    val extensions = setOf("jpg", "jpeg", "png", "webp")
    ZipInputStream(input.buffered()).use { zip ->
        while (true) {
            checkCancelled()
            val entry = zip.nextEntry ?: break
            require(++entries <= 10_000) { "压缩包包含过多文件" }
            val path = entry.name
            val parts = path.split('/')
            require(!path.startsWith('/') && '\\' !in path && ".." !in parts) { "压缩包路径无效" }
            val isPage = !entry.isDirectory && parts.none { it.startsWith('.') || it == "__MACOSX" } &&
                path.substringAfterLast('.', "").lowercase() in extensions
            val output = if (isPage) ByteArrayOutputStream() else null
            while (true) {
                checkCancelled()
                val count = zip.read(buffer)
                if (count < 0) break
                expanded += count
                require(expanded <= DocumentTools.MAX_EXPANDED) { "解压后文件过大" }
                if (output != null) {
                    require(output.size() + count <= 16 * 1024 * 1024) { "漫画单页超过 16 MB，请先缩小图片" }
                    output.write(buffer, 0, count)
                }
            }
            if (output != null) {
                val bytes = output.toByteArray()
                require(bytes.isNotEmpty()) { "漫画包含空白图片文件" }
                val hash = DocumentTools.digest(bytes.inputStream(), checkCancelled = checkCancelled)
                require(pages.put(path, hash) == null) { "漫画包含重名页面" }
                if (installed.add(hash)) bytes.inputStream().use { imageSink(hash, it) }
            }
        }
    }
    require(pages.isNotEmpty()) { "CBZ 中没有 JPG、PNG 或 WebP 漫画页" }
    val collator = (Collator.getInstance(ULocale.ROOT) as RuleBasedCollator).apply { numericCollation = true }
    val ordered = pages.keys.sortedWith(compareBy(collator) { path: String -> path }.thenBy { it })
    return LocalChapter("0", "全册", ordered.map { "novelia-image:${pages.getValue(it)}" })
}
