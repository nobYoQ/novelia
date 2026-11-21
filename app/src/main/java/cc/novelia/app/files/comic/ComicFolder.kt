package cc.novelia.app.files.comic

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

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
                } else if (isComicImage(name) || path.equals("ComicInfo.xml", true)) {
                    require(pages.put(path, documentUri(child)) == null) { "漫画包含重名页面" }
                }
            }
        }
    }
    require(pages.keys.any(::isComicImage)) { "文件夹中没有可读取的漫画图片，请选择包含图片的目录" }
    var expanded = 0L
    ZipOutputStream(target.outputStream().buffered()).use { zip ->
        // 固定顺序和时间，使同一文件夹重选时能复用原书和阅读位置。
        for ((path, uri) in pages) {
            checkCancelled()
            zip.putNextEntry(ZipEntry(path).apply { time = 0L })
            requireNotNull(resolver.openInputStream(uri)) { "漫画页无法读取" }.use { input ->
                expanded += transferComic(input, zip, minOf(comicPageLimit.toLong(), comicExpandedLimit - expanded), checkCancelled)
            }
            zip.closeEntry()
            require(target.length() <= comicInputLimit) { "漫画文件夹超过 1 GiB" }
        }
    }
    require(target.length() <= comicInputLimit) { "漫画文件夹超过 1 GiB" }
    return title
}
