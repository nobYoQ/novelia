package cc.novelia.app.data.documents

import cc.novelia.app.data.model.LocalChapter
import cc.novelia.app.data.model.LocalDocument
import cc.novelia.app.data.model.LocalReadingContent
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.storage.hashName
import java.io.File
import java.io.IOException
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
        val index = document.copy(chapters = document.chapters.map { it.descriptor() }, images = emptyMap(), chapterFiles = files)
        write(manifest(document.id), appJson.encodeToString(index))
        return index
    }

    /** 外置章节除校验哈希外还检查 ID 和标题，防止目录错配；旧格式直接使用内嵌正文。 */
    fun chapter(index: LocalDocument, chapterId: String): LocalChapter {
        val descriptor = index.chapters.firstOrNull { it.id == chapterId } ?: error("本地章节不存在")
        val hash = index.chapterFiles[chapterId] ?: return descriptor
        val text = read(chapterFile(index.id, hash))
        require(hashName(text) == hash) { "本地章节文件校验失败" }
        return appJson.decodeFromString<LocalChapter>(text).also {
            require(it.id == chapterId && it.title == descriptor.title) { "本地章节文件与目录不一致" }
        }
    }

    fun full(index: LocalDocument, checkCancelled: () -> Unit = {}): LocalDocument = index.copy(
        chapters = index.chapters.map { checkCancelled(); chapter(index, it.id) }, chapterFiles = emptyMap())

    /** 只升级当前章；先提交新正文，再原子替换目录，失败时仍可读取旧章。 */
    fun replaceChapter(index: LocalDocument, chapter: LocalChapter): LocalDocument {
        require(index.chapterFiles.isNotEmpty() && index.chapters.any { it.id == chapter.id && it.title == chapter.title })
        val text = appJson.encodeToString(chapter)
        val hash = hashName(text)
        val file = chapterFile(index.id, hash)
        if (!file.isFile) { file.parentFile?.mkdirs(); write(file, text) }
        val next = index.copy(chapters = index.chapters.map { if (it.id == chapter.id) chapter.descriptor() else it },
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

    private fun LocalChapter.descriptor() = copy(paragraphs = emptyList(), readingContent = LocalReadingContent())

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
