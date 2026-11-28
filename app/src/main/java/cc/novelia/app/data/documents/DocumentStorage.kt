package cc.novelia.app.data.documents

import cc.novelia.app.data.model.LocalChapter
import cc.novelia.app.data.model.LocalDocument
import cc.novelia.app.data.model.LocalReadingContent
import cc.novelia.app.data.model.displayChapterCount
import cc.novelia.app.data.model.findIllustrationParagraphs
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.storage.hashName
import cc.novelia.app.files.epub.EPUB_TITLE_VERSION
import cc.novelia.app.files.epub.EPUB_CONTENT_VERSION
import cc.novelia.app.files.epub.recoverEpubChapter
import cc.novelia.app.files.epub.chapterIds
import cc.novelia.app.files.epub.readEpubAuthors
import cc.novelia.app.files.epub.readEpubDirectory
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString

/**
 * 将本地文档拆为轻量目录与按内容哈希命名的章节文件，阅读时只加载当前章。
 * 保存顺序是先写完全部章节、最后提交目录；中途失败不会发布指向半成品的新目录。
 * read/write 由外层注入原子文件实现，并由 LocalStore 的文档锁串行保护。
 */
internal class DocumentStorage(
    private val directory: File,
    private val read: (File) -> String,
    private val write: (File, String) -> Unit
) {
    fun exists(id: String): Boolean = manifest(id).let { it.isFile || File(it.path + ".bak").isFile }

    /** 仅列出已提交目录，不读取正文；保留副本即使已移出书架也参加重复导入检测。 */
    fun ids(): List<String> = directory.listFiles().orEmpty().asSequence()
        .filter { it.isFile && (it.name.endsWith(".json") || it.name.endsWith(".json.bak")) }
        .map { it.name.removeSuffix(".bak").removeSuffix(".json") }
        .filter { it != "source-index" && it.matches(Regex("[a-zA-Z0-9-]+")) }
        .distinct().sorted().toList()

    /** 读取目录并惰性迁移旧的整本格式；迁移遇到 IO 错误时仍返回可读旧文档。 */
    fun index(id: String): LocalDocument {
        val document = appJson.decodeFromString<LocalDocument>(read(manifest(id)))
        require(document.id == id) { "本地文档标识不一致" }
        if (document.chapterFiles.isNotEmpty()) {
            require(document.chapterFiles.keys == document.chapters.map { it.id }.toSet()) { "本地章节索引不完整" }
            return document
        }
        // 旧导入和恢复的可移植备份只迁移一次，不改动原始文件。
        // 存储空间不足时，仍应允许读取原本可用的旧格式文档。
        return try { save(document) } catch (_: IOException) { document }
    }

    /** 输入必须含完整正文，不能把空正文的目录对象当作新文档保存；同内容章节可复用文件。 */
    fun save(document: LocalDocument, checkCancelled: () -> Unit = {}): LocalDocument {
        require(document.chapterFiles.isEmpty()) { "保存文档需要完整章节正文" }
        require(document.chapters.map { it.id }.distinct().size == document.chapters.size) { "本地章节标识重复" }
        val files = document.chapters.associate { chapter ->
            checkCancelled()
            val text = appJson.encodeToString(chapter)
            val hash = hashName(text)
            val file = chapterFile(document.id, hash)
            if (!file.isFile) { file.parentFile?.mkdirs(); write(file, text) }
            chapter.id to hash
        }
        checkCancelled()
        val index = document.copy(chapters = document.chapters.map { checkCancelled(); it.descriptor(document.format) },
            images = emptyMap(), chapterFiles = files)
        checkCancelled()
        write(manifest(document.id), appJson.encodeToString(index))
        return index
    }

    /** 外置章节校验哈希和 ID；升级过的 EPUB 以目录中的标题为准。 */
    fun chapter(index: LocalDocument, chapterId: String): LocalChapter {
        val descriptor = index.chapters.firstOrNull { it.id == chapterId } ?: error("本地章节不存在")
        return chapter(index, descriptor)
    }

    fun displayChapterCount(index: LocalDocument, checkCancelled: () -> Unit): Int = index.displayChapterCount { descriptor ->
        checkCancelled()
        chapter(index, descriptor)
    }

    private fun chapter(index: LocalDocument, descriptor: LocalChapter): LocalChapter {
        val hash = index.chapterFiles[descriptor.id] ?: return descriptor
        val text = read(chapterFile(index.id, hash))
        require(hashName(text) == hash) { "本地章节文件校验失败" }
        val chapter = appJson.decodeFromString<LocalChapter>(text)
        val indexHasEpubTitles = index.format == "epub" && index.epubTitleVersion > 0
        require(chapter.id == descriptor.id && (indexHasEpubTitles || chapter.title == descriptor.title)) { "本地章节文件与目录不一致" }
        return if (indexHasEpubTitles && chapter.title != descriptor.title) chapter.copy(title = descriptor.title) else chapter
    }

    /** 从原件恢复整本目录，只提交轻量索引，保留正文文件和所有定位标识。 */
    fun recoverEpubTitles(index: LocalDocument, source: File): LocalDocument {
        if (index.format != "epub" || index.epubTitleVersion >= EPUB_TITLE_VERSION || index.chapterFiles.isEmpty() || !source.isFile) return index
        val directory = readEpubDirectory(source, index.chapterFiles.keys)
        if (directory.titles.keys != index.chapterFiles.keys) return index
        val next = index.copy(chapters = index.chapters.map { it.copy(title = directory.titles.getValue(it.id)) },
            epubTitleVersion = EPUB_TITLE_VERSION, epubNavigationIds = directory.navigation.chapterIds(), epubNavigation = directory.navigation)
        write(manifest(index.id), appJson.encodeToString(next))
        return next
    }

    fun full(index: LocalDocument, checkCancelled: () -> Unit = {}): LocalDocument = index.copy(
        chapters = index.chapters.map { checkCancelled(); chapter(index, it.id) }, chapterFiles = emptyMap())

    fun recoverEpubAuthors(index: LocalDocument, source: File, checkCancelled: () -> Unit): LocalDocument {
        val next = index.copy(authors = readEpubAuthors(source, checkCancelled))
        checkCancelled()
        write(manifest(index.id), appJson.encodeToString(next))
        return next
    }

    /** 只升级当前章；先提交新正文，再原子替换目录，失败时仍可读取旧章。 */
    fun replaceChapter(index: LocalDocument, chapter: LocalChapter): LocalDocument {
        require(index.chapterFiles.isNotEmpty() && index.chapters.any { it.id == chapter.id && it.title == chapter.title })
        val text = appJson.encodeToString(chapter)
        val hash = hashName(text)
        val file = chapterFile(index.id, hash)
        if (!file.isFile) { file.parentFile?.mkdirs(); write(file, text) }
        val next = index.copy(chapters = index.chapters.map { if (it.id == chapter.id) chapter.descriptor(index.format) else it },
            chapterFiles = index.chapterFiles + (chapter.id to hash))
        write(manifest(index.id), appJson.encodeToString(next))
        return next
    }

    fun recordDownloadMode(index: LocalDocument, mode: String): LocalDocument {
        require(mode in setOf("zh", "jp", "jp-zh", "zh-jp"))
        val next = index.copy(downloadMode = mode)
        write(manifest(index.id), appJson.encodeToString(next))
        return next
    }

    private fun LocalChapter.descriptor(format: String): LocalChapter {
        val images = if (format == "epub") findIllustrationParagraphs() else null
        val anchors = if (images.isNullOrEmpty()) emptyMap() else readingContent.anchors
        return copy(paragraphs = emptyList(), readingContent = LocalReadingContent(anchors = anchors), illustrationParagraphs = images)
    }

    private fun LocalChapter.needsIllustrationAnchors(fragments: List<String>): Boolean =
        epubContentVersion < EPUB_CONTENT_VERSION && illustrationParagraphs?.isEmpty() != true &&
            fragments.any { it !in readingContent.anchors }

    /** 旧索引只需从已保存的正文补一次。单章缺失时保留未知状态，其余目录项仍可使用。 */
    fun recoverIllustrationIndex(index: LocalDocument, persist: Boolean, source: File? = null, checkCancelled: () -> Unit): LocalDocument {
        if (index.format != "epub" || index.chapterFiles.isEmpty()) return index
        // 较早的正文只有图片段落，没有保存小节锚点。仅在需要分辨小节时从保留的原件取回定位。
        val legacySplits = if (index.chapters.any { it.epubContentVersion < EPUB_CONTENT_VERSION } && source?.isFile == true)
            index.epubNavigation.mapNotNull { it.target }.distinct().groupBy { it.chapterId }.filterValues { it.size > 1 }
        else emptyMap()
        if (legacySplits.isEmpty() && index.chapters.none { it.illustrationParagraphs == null }) return index
        var changed = false
        val chapters = index.chapters.map { entry ->
            checkCancelled()
            val fragments = legacySplits[entry.id].orEmpty().mapNotNull { it.fragment }
            if (entry.illustrationParagraphs != null && !entry.needsIllustrationAnchors(fragments)) return@map entry
            val chapter = try { chapter(index, entry) }
            catch (_: IOException) { return@map entry }
            catch (_: IllegalArgumentException) { return@map entry }
            var descriptor = if (entry.illustrationParagraphs == null) chapter.descriptor(index.format) else entry
            if (source != null && descriptor.needsIllustrationAnchors(fragments)) {
                val recovered = try { recoverEpubChapter(source, chapter, index.downloadMode, checkCancelled) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
                if (recovered != null) descriptor = descriptor.copy(readingContent = LocalReadingContent(anchors = recovered.readingContent.anchors))
            }
            if (descriptor != entry) changed = true
            descriptor
        }
        if (!changed) return index
        val next = index.copy(chapters = chapters)
        checkCancelled()
        if (persist) {
            try { write(manifest(index.id), appJson.encodeToString(next)) }
            catch (_: IOException) { /* 空间不足时，本次仍可使用内存中的标记。 */ }
        }
        return next
    }

    /** 只更新目录名称；分卷 ID、源文件哈希和外置章节文件均保持稳定。 */
    fun rename(index: LocalDocument, name: String): LocalDocument {
        val title = name.trim()
        require(title.isNotEmpty()) { "小说名称不能为空" }
        val renamed = index.copy(name = title)
        write(manifest(index.id), appJson.encodeToString(renamed))
        return renamed
    }

    fun remove(id: String) {
        val file = manifest(id)
        listOf(file, File(file.path + ".bak"), File(file.path + ".new")).forEach { it.delete() }
        val chapters = File(directory, "$id-chapters")
        chapters.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
        chapters.delete()
    }

    private fun manifest(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]+")))
        return File(directory, "$id.json")
    }
    private fun chapterFile(id: String, hash: String): File {
        manifest(id)
        require(hash.matches(Regex("[a-f0-9]{64}")))
        return File(directory, "$id-chapters/$hash.json")
    }
}
