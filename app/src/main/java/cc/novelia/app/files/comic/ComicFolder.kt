package cc.novelia.app.files.comic

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import cc.novelia.app.files.DocumentTools
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 通过系统授予的目录读取图片，生成可导出、可重复导入的 CBZ 副本。 */
internal fun packComicFolder(resolver: ContentResolver, tree: Uri, target: File, checkCancelled: () -> Unit): String {
    val root = DocumentsContract.getTreeDocumentId(tree)
    fun documentUri(id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id)
    val title = resolver.query(documentUri(root), arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }?.takeIf { it.isNotBlank() } ?: "本地漫画"
    val pages = sortedMapOf<String, Uri>()
    val visited = mutableSetOf(root)
    val pending = ArrayDeque<Pair<String, String>>().apply { add(root to "") }
    var entries = 0
    val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
    while (pending.isNotEmpty()) {
        checkCancelled()
        val (id, prefix) = pending.removeFirst()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        val cursor = requireNotNull(resolver.query(children, columns, null, null, null)) { "无法读取漫画文件夹" }
        cursor.use {
            while (it.moveToNext()) {
                checkCancelled()
                require(++entries <= 10_000) { "文件夹包含过多文件" }
                val child = it.getString(0)
                val name = it.getString(1) ?: continue
                if (name.isBlank() || name.startsWith('.') || name == "__MACOSX") continue
                require('/' !in name && '\\' !in name) { "漫画文件名无效" }
                val path = prefix + name
                require(path.length <= 4096) { "漫画文件路径过长" }
                if (it.getString(2) == Document.MIME_TYPE_DIR) {
                    if (visited.add(child)) pending.add(child to "$path/")
                } else if (isComicImage(name)) {
                    require(pages.put(path, documentUri(child)) == null) { "漫画包含重名页面" }
                }
            }
        }
    }
    require(pages.isNotEmpty()) { "文件夹中没有可读取的漫画图片，请选择包含图片的目录" }
    val buffer = ByteArray(32 * 1024)
    var expanded = 0L
    ZipOutputStream(target.outputStream().buffered()).use { zip ->
        // 固定顺序和时间，使同一文件夹重选时能复用原书和阅读位置。
        for ((path, uri) in pages) {
            checkCancelled()
            zip.putNextEntry(ZipEntry(path).apply { time = 0L })
            var size = 0
            requireNotNull(resolver.openInputStream(uri)) { "漫画页无法读取" }.use { input ->
                while (true) {
                    checkCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    expanded += count
                    require(size <= maxComicPageBytes && expanded <= DocumentTools.MAX_EXPANDED) { "漫画图片超过导入大小限制" }
                    zip.write(buffer, 0, count)
                }
            }
            zip.closeEntry()
            DocumentTools.requireImportSize(target.length())
        }
    }
    DocumentTools.requireImportSize(target.length())
    return title
}
