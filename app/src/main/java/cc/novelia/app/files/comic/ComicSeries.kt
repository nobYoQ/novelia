package cc.novelia.app.files.comic

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import cc.novelia.app.data.model.*
import cc.novelia.app.data.storage.LocalStore
import cc.novelia.app.files.DocumentTools
import cc.novelia.app.files.importing.DocumentImportResult
import cc.novelia.app.files.importing.documentImportLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

private data class TreeEntry(val id: String, val name: String, val folder: Boolean)

private class SeriesTree(val resolver: ContentResolver, val tree: Uri, val check: () -> Unit) {
    val root: String = DocumentsContract.getTreeDocumentId(tree)
    private var visited = 0
    fun uri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
    fun open(id: String) = requireNotNull(resolver.openInputStream(uri(id))) { "无法读取漫画文件" }
    fun list(id: String): List<TreeEntry> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        return requireNotNull(resolver.query(children, columns, null, null, null)) { "无法读取系列文件夹" }.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    check()
                    require(++visited <= 10000) { "目录中的文件过多" }
                    val name = cursor.getString(1) ?: continue
                    if (name.isBlank() || name.startsWith('.') || name == "__MACOSX") continue
                    require('/' !in name && '\\' !in name && name.length <= 255) { "漫画文件名无效" }
                    add(TreeEntry(cursor.getString(0), name, cursor.getString(2) == Document.MIME_TYPE_DIR))
                }
            }
        }
    }
    fun copyFolder(id: String, put: (String, InputStream) -> Unit) {
        val pending = ArrayDeque<Pair<String,String>>().apply { add(id to "") }
        val seen = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            val (dir, prefix) = pending.removeFirst()
            require(seen.add(dir) && prefix.length < 4096) { "漫画目录循环或层级过深" }
            for (entry in list(dir)) {
                if (entry.folder) pending.add(entry.id to prefix + entry.name + "/")
                else if (isComicImage(entry.name)) open(entry.id).use { put(prefix + entry.name, it) }
            }
        }
    }
}

private fun LocalStore.seriesLinks() = context.getSharedPreferences("comic-series-trees", Context.MODE_PRIVATE)
internal fun LocalStore.seriesTree(id: String): Uri? = seriesLinks().getString(id, null)?.let(Uri::parse)

/** Append new chapters into an offline CBZ. Old paths always survive the atomic source replacement. */
internal suspend fun importComicSeries(store: LocalStore, tree: Uri, targetId: String? = null,
    progress: (String) -> Unit = {}): DocumentImportResult = withContext(Dispatchers.IO) {
    val job = coroutineContext
    val check = { job.ensureActive() }
    documentImportLock.withLock {
        val links = store.seriesLinks()
        val linked = targetId ?: links.all.entries.firstOrNull {
            it.value == tree.toString() && store.documentSource(it.key, "cbz").isFile
        }?.key
        val existing = linked?.takeIf { store.documentSource(it, "cbz").isFile }?.let { store.documentIndex(it) }
        fun rememberTree(id: String) {
            val editor = links.edit()
            links.all.filter { (other, uri) -> other != id && uri == tree.toString() }.keys.forEach { editor.remove(it) }
            editor.putString(id, tree.toString()).apply()
        }
        require(targetId == null || existing?.comicSeries == true) { "系列原件不可用" }
        val browser = SeriesTree(store.context.contentResolver, tree, check)
        val items = browser.list(browser.root)
        val title = browser.resolver.query(browser.uri(browser.root), arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "本地系列"
        val previous = existing?.chapters?.map { it.title }?.toSet().orEmpty()
        val incoming = items.filter { it.folder || it.name.substringAfterLast('.').lowercase() in comicArchiveFormats }
            .associateBy { if (it.folder) it.name else it.name.substringBeforeLast('.') }
        require(incoming.size == items.count { it.folder || it.name.substringAfterLast('.').lowercase() in comicArchiveFormats }) { "存在同名话数，请先区分文件名" }
        val additions = incoming.keys.minus(previous).sortedWith(comicOrder())
        if (existing != null && additions.isEmpty()) {
            val ref = BookRef("local", existing.id)
            val restoredToShelf = store.state.value.books.none { it.book.ref == ref }
            if (restoredToShelf) store.saveBook(BookCard(ref, existing.name,
                cover = existing.coverImage?.let { store.documentImage(existing.id, it).absolutePath },
                subtitle = "漫画系列 · " + existing.chapters.size + " 话", total = existing.chapters.size,
                authors = listOfNotNull(existing.comicMetadata?.writer?.takeIf(String::isNotBlank)), medium = BookMedium.Comic))
            rememberTree(existing.id)
            return@withLock DocumentImportResult(ref, restoredToShelf)
        }
        require(additions.isNotEmpty()) { "请选择包含分话压缩包或分话图片目录的系列文件夹" }
        val id = existing?.id ?: UUID.randomUUID().toString()
        val source = store.documentSource(id, "cbz")
        val stage = Files.createTempDirectory(store.context.cacheDir.toPath(), "series-").toFile()
        val packed = File.createTempFile("series-", ".tmp", source.parentFile)
        try {
            var expanded = 0L
            var count = 0
            ZipOutputStream(packed.outputStream().buffered()).use { out ->
                out.setLevel(java.util.zip.Deflater.BEST_SPEED)
                fun put(path: String, input: InputStream) {
                    require(++count <= 10000 && path.length <= 4096) { "系列页数过多或路径过长" }
                    out.putNextEntry(ZipEntry(path).apply { time = 0 })
                    expanded += transferComic(input, out, minOf(comicPageLimit.toLong(), comicExpandedLimit - expanded), check)
                    out.closeEntry()
                    require(packed.length() <= comicInputLimit) { "系列超过 1 GiB" }
                }
                if (existing != null) ZipFile(source).use { old ->
                    for (entry in old.entries()) old.getInputStream(entry).use { put(entry.name, it) }
                } else {
                    put(seriesMarker, "1".byteInputStream())
                    for (entry in items.filter { !it.folder && (it.name.equals("ComicInfo.xml", true) || it.name.equals("cover.jpg", true) || it.name.equals("cover.png", true)) })
                        browser.open(entry.id).use { put(entry.name, it) }
                }
                for (chapter in additions) {
                    progress("正在导入 " + chapter)
                    val item = incoming.getValue(chapter)
                    val before = count
                    if (item.folder) browser.copyFolder(item.id) { path, input -> put(chapter + "/" + path, input) }
                    else {
                        val part = File(stage, "chapter.tmp")
                        browser.open(item.id).use { input -> part.outputStream().use { transferComic(input, it, comicInputLimit, check) } }
                        visitComicArchive(part, item.name.substringAfterLast('.').lowercase(), check) { path, _, open ->
                            if (isComicImage(path)) open().use { put(chapter + "/" + path, it) }
                            false
                        }
                        part.delete()
                    }
                    require(count > before) { "章节中没有漫画页：" + chapter }
                }
            }
            require(packed.length() <= comicInputLimit) { "系列超过 1 GiB" }
            val digest = packed.inputStream().use { DocumentTools.digest(it, comicInputLimit, check) }
            val parsed = indexComic(packed, title + ".cbz", digest, stage, check)
            val doc = parsed.copy(id = id, name = existing?.name ?: parsed.name)
            val coverHash = requireNotNull(doc.coverImage)
            val cover = store.documentImage(id, coverHash)
            cover.parentFile?.mkdirs()
            if (!cover.exists()) Files.move(File(stage, coverHash).toPath(), cover.toPath())
            check()
            store.publishComicSeries(doc, packed)
            val ref = BookRef("local", id)
            val previousBook = store.state.value.books.firstOrNull { it.book.ref == ref }
            val card = (previousBook?.book ?: BookCard(ref, doc.name)).copy(cover = cover.absolutePath,
                authors = listOfNotNull(doc.comicMetadata?.writer?.takeIf(String::isNotBlank)), medium = BookMedium.Comic,
                subtitle = "漫画系列 · " + doc.chapters.size + " 话", total = doc.chapters.size)
            store.saveBook(card, previousBook?.folder ?: DEFAULT_FOLDER)
            rememberTree(id)
            DocumentImportResult(ref, true)
        } catch (e: Exception) {
            if (existing == null) store.removeDocument(id)
            throw e
        } finally { packed.delete(); stage.deleteRecursively() }
    }
}
