package cc.novelia.app.data.backup

import cc.novelia.app.data.catalog.KeywordEntry
import cc.novelia.app.data.catalog.KeywordLibrary
import cc.novelia.app.data.catalog.KeywordLibraryFormat
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.LocalDocument
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.data.storage.appJson
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable data class BackupAsset(val bytes: Long, val sha256: String)
@Serializable data class LibraryBackupManifest(
    val format: String,
    val version: Int,
    val createdAt: Long,
    val library: LibraryState,
    val documents: List<String>,
    val missingDocuments: List<String> = emptyList(),
    val originalsIncluded: Boolean = false,
    val keywords: List<KeywordEntry> = emptyList(),
    val assets: Map<String, BackupAsset>,
    val keywordCategories: List<String>? = null,
)

data class BackupPreview(
    val stagingId: String, val createdAt: Long, val books: Int, val positions: Int,
    val notes: Int, val documents: Int, val missingDocuments: Int, val keywords: Int,
    val bytes: Long, val originalsIncluded: Boolean
)

/**
 * 备份格式的流式读写和验证边界，只接受清单、文档和图片的白名单路径。
 * 限制单文件、总解压字节和条目数，并校验 SHA-256、引用关系和业务字段；
 * ZIP 元数据不能替代实际读取计数，归档路径也不能直接成为任意文件写入目标。
 */
internal object LibraryBackupArchive {
    const val MANIFEST = "manifest.json"
    const val MAX_ENTRY_BYTES = 128L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 1024L * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 16L * 1024 * 1024
    private const val MAX_ENTRIES = 30_000
    private val id = Regex("[a-zA-Z0-9-]{1,128}")
    private val documentPath = Regex("documents/[a-zA-Z0-9-]{1,128}\\.(json|epub|txt|srt)")
    private val imagePath = Regex("documents/[a-zA-Z0-9-]{1,128}-images/[a-f0-9]{64}")

    fun requireSafePath(path: String) {
        require(path == MANIFEST || documentPath.matches(path) || imagePath.matches(path)) { "备份包含不安全或不支持的路径" }
    }

    fun digest(file: File, checkCancelled: () -> Unit = {}): BackupAsset = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val size = transfer(input, null, MAX_ENTRY_BYTES, checkCancelled) { bytes, count -> digest.update(bytes, 0, count) }
        BackupAsset(size, digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
    }

    fun write(output: OutputStream, manifest: LibraryBackupManifest, files: Map<String, File>, checkCancelled: () -> Unit = {}) {
        require(files.keys == manifest.assets.keys) { "备份文件索引不完整" }
        val metadata = appJson.encodeToString(manifest).toByteArray(Charsets.UTF_8)
        require(metadata.size <= MAX_MANIFEST_BYTES) { "阅读资料索引超过 16 MB，无法备份" }
        require(files.size + 1 <= MAX_ENTRIES && manifest.assets.values.sumOf { it.bytes } + metadata.size <= MAX_TOTAL_BYTES) { "备份超过 1 GB 或文件数量过多" }
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST)); zip.write(metadata); zip.closeEntry()
            files.forEach { (name, file) ->
                checkCancelled(); requireSafePath(name)
                // 检测导出快照组装期间文档是否被修改。
                require(digest(file, checkCancelled) == manifest.assets[name]) { "备份期间文件发生变化，请重试" }
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { transfer(it, zip, MAX_ENTRY_BYTES, checkCancelled) }
                zip.closeEntry()
            }
        }
    }

    fun extract(input: InputStream, staging: File, checkCancelled: () -> Unit = {}): LibraryBackupManifest {
        require(staging.isDirectory && staging.list().orEmpty().isEmpty())
        val names = mutableSetOf<String>()
        var total = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                checkCancelled()
                val entry = zip.nextEntry ?: break
                requireSafePath(entry.name)
                require(!entry.isDirectory && names.add(entry.name) && names.size <= MAX_ENTRIES) { "备份包含重复路径或文件数量过多" }
                val maximum = minOf(if (entry.name == MANIFEST) MAX_MANIFEST_BYTES else MAX_ENTRY_BYTES, MAX_TOTAL_BYTES - total)
                val target = File(staging, entry.name)
                target.parentFile?.mkdirs()
                target.outputStream().use { total += transfer(zip, it, maximum, checkCancelled) }
                zip.closeEntry()
            }
        }
        require(MANIFEST in names) { "这不是 Novelia 阅读资料备份" }
        return validate(staging, names - MANIFEST, checkCancelled)
    }

    /**
     * 验证“清单列出的资源”和“正文实际引用的资源”完全一致，再允许恢复层使用。
     * extracted 在首次解包时额外比对 ZIP 条目集合；恢复前再次调用可检查暂存内容是否损坏。
     */
    fun validate(staging: File, extracted: Set<String>? = null, checkCancelled: () -> Unit = {}): LibraryBackupManifest {
        val manifestFile = File(staging, MANIFEST)
        require(manifestFile.length() <= MAX_MANIFEST_BYTES) { "备份索引过大" }
        val manifest = appJson.decodeFromString<LibraryBackupManifest>(manifestFile.readText(Charsets.UTF_8))
        require(manifest.format == "novelia-library" && manifest.version == 1) { "不支持此备份格式或版本，请升级应用后重试" }
        require(manifest.assets.size <= MAX_ENTRIES && manifest.assets.values.sumOf { it.bytes } <= MAX_TOTAL_BYTES) { "备份体积过大" }
        extracted?.let { require(it == manifest.assets.keys) { "备份文件与索引不一致" } }
        manifest.assets.forEach { (path, asset) ->
            checkCancelled(); requireSafePath(path)
            require(path != MANIFEST && asset.bytes in 0..MAX_ENTRY_BYTES && asset.sha256.matches(Regex("[a-f0-9]{64}"))) { "备份文件索引无效" }
            val file = File(staging, path)
            require(file.isFile && digest(file, checkCancelled) == asset) { "备份文件缺失或校验失败" }
        }
        val documentIds = manifest.documents.toSet()
        val missing = manifest.missingDocuments.toSet()
        require(documentIds.size == manifest.documents.size && missing.size == manifest.missingDocuments.size && documentIds.intersect(missing).isEmpty()) { "备份文档索引重复" }
        require((documentIds + missing).all { id.matches(it) }) { "备份文档标识无效" }
        val state = manifest.library
        require(state.pending.isEmpty() && state.downloads.isEmpty() && state.syncStatus.isEmpty()) { "备份不应包含账号队列或运行中的任务" }
        require(state.books.map { it.book.ref.key }.distinct().size == state.books.size && state.notes.map { it.id }.distinct().size == state.notes.size) { "备份含重复的书籍或笔记标识" }
        require(state.theme in setOf("system", "light", "dark")) { "备份主题设置无效" }
        (listOf(state.reader) + state.bookSettings.values).forEach { settings ->
            require(settings.fontSize in 10f..60f && settings.lineHeight in ReaderSettings.MIN_LINE_HEIGHT..4f && settings.width in 200f..2000f &&
                settings.engines.size == 3 && settings.engines.toSet() == setOf("sakura", "gpt", "youdao") && settings.speechRate in .1f..5f &&
                settings.secondaryAlpha in 0f..1f && (settings.brightness == -1f || settings.brightness in 0f..1f)) { "备份阅读设置无效" }
        }
        require(state.positions.values.all { it.index >= 0 && it.offset >= 0 && it.textOffset >= 0 }) { "备份阅读进度无效" }
        require(localDocumentIds(state).all { it in documentIds || it in missing }) { "备份包含未列入索引的本地文档" }
        val books = state.books.associateBy { it.book.ref.key }
        state.books.forEach { book ->
            require(book.book.ref.provider.isNotBlank() && book.book.ref.id.isNotBlank()) { "备份书籍标识无效" }
            book.parentWenkuKey?.let { parent -> require(book.book.ref.isLocal && books[parent]?.book?.ref?.isWenku == true) { "分卷挂载关系无效" } }
            require(book.volumeOrder.distinct().size == book.volumeOrder.size && book.volumeOrder.all { books[it]?.parentWenkuKey == book.book.ref.key }) { "分卷排序索引无效" }
        }
        val expected = mutableSetOf<String>()
        documentIds.forEach { documentId ->
            checkCancelled()
            val path = "documents/$documentId.json"
            require(path in manifest.assets) { "备份缺少解析文档" }; expected += path
            val document = appJson.decodeFromString<LocalDocument>(File(staging, path).readText(Charsets.UTF_8))
            require(document.id == documentId && document.format in setOf("epub", "txt", "srt") && document.images.isEmpty() && document.chapterFiles.isEmpty()) { "备份解析文档无效" }
            require(document.chapters.all { it.id.isNotBlank() } && document.chapters.map { it.id }.distinct().size == document.chapters.size) { "备份章节标识无效或重复" }
            val imageIds = document.chapters.flatMap { it.paragraphs }.filter { it.startsWith("novelia-image:") }.map { it.removePrefix("novelia-image:") }.toSet() + listOfNotNull(document.coverImage)
            imageIds.forEach { hash ->
                val image = "documents/$documentId-images/$hash"
                requireSafePath(image); require(image in manifest.assets) { "备份缺少正文插图" }; expected += image
            }
            listOf("epub", "txt", "srt").forEach { format ->
                val source = "documents/$documentId.$format"
                if (source in manifest.assets) { require(manifest.originalsIncluded) { "原文件索引无效" }; expected += source }
            }
        }
        require(expected == manifest.assets.keys) { "备份包含未引用的文档或图片" }
        KeywordLibraryFormat.validate(manifest.keywordLibrary())
        return manifest
    }

    private fun transfer(input: InputStream, output: OutputStream?, limit: Long, checkCancelled: () -> Unit, consume: (ByteArray, Int) -> Unit = { _, _ -> }): Long {
        val bytes = ByteArray(65536); var total = 0L
        while (true) {
            checkCancelled()
            val count = input.read(bytes); if (count < 0) break
            total += count
            require(total <= limit) { "备份文件过大（单文件上限 128 MB，总计上限 1 GB）" }
            consume(bytes, count); output?.write(bytes, 0, count)
        }
        return total
    }
}

internal fun LibraryBackupManifest.keywordLibrary(): KeywordLibrary = keywordCategories?.let { KeywordLibrary(keywords, it) }
    ?: KeywordLibrary.fromLegacy(keywords, addDefaults = false)

internal fun localDocumentIds(state: LibraryState): Set<String> = (
    state.books.map { it.book.ref.key } + state.positions.keys + state.notes.map { it.key } + state.bookSettings.keys + state.personalGlossaries.keys
).filter { it.startsWith("local/") }.map { it.removePrefix("local/") }.toSet()

internal fun LibraryState.forBackup(): LibraryState = copy(pending = emptyList(), downloads = emptyList(), syncStatus = emptyMap())

/**
 * 合并而非覆盖：已有书目、偏好和草稿优先，阅读进度按 updatedAt 取较新值，笔记按 ID 去重。
 * 没有书目、进度和笔记的空书库还会继承备份全局偏好；运行中的下载、同步队列及状态
 * 始终沿用当前安装的数据，备份不能重新发起另一设备的后台操作。
 */
internal fun mergeLibraryBackup(current: LibraryState, imported: LibraryState): LibraryState {
    val base = if (current.books.isEmpty() && current.positions.isEmpty() && current.notes.isEmpty()) imported.forBackup() else current
    val books = current.books + imported.books.filter { item -> current.books.none { it.book.ref == item.book.ref } }
    val booksByKey = books.associateBy { it.book.ref.key }
    val merged = base.copy(
        books = books.map { book -> book.copy(volumeOrder = (book.volumeOrder + imported.books.firstOrNull { it.book.ref == book.book.ref }?.volumeOrder.orEmpty()).distinct().filter { booksByKey[it]?.parentWenkuKey == book.book.ref.key }) },
        folders = (current.folders + imported.folders).distinct(),
        positions = (current.positions.keys + imported.positions.keys).associateWith { key -> listOfNotNull(current.positions[key], imported.positions[key]).maxBy { it.updatedAt } },
        notes = current.notes + imported.notes.filter { note -> current.notes.none { it.id == note.id } },
        bookSettings = imported.bookSettings + current.bookSettings,
        personalGlossaries = (current.personalGlossaries.keys + imported.personalGlossaries.keys).associateWith { key -> imported.personalGlossaries[key].orEmpty() + current.personalGlossaries[key].orEmpty() },
        blockedBooks = current.blockedBooks + imported.blockedBooks, blockedTags = current.blockedTags + imported.blockedTags,
        blockedAuthors = current.blockedAuthors + imported.blockedAuthors,
        blockedUsers = current.blockedUsers + imported.blockedUsers,
        recentSearches = (current.recentSearches + imported.recentSearches).distinct().take(20),
        savedSearches = (current.savedSearches + imported.savedSearches).distinct(),
        savedArticles = current.savedArticles + imported.savedArticles.filter { post -> current.savedArticles.none { it.id == post.id } },
        drafts = imported.drafts + current.drafts,
        updateSnapshots = (imported.updateSnapshots + current.updateSnapshots).filterKeys { it in booksByKey },
        bookUpdates = (imported.bookUpdates + current.bookUpdates).filterKeys { it in booksByKey },
        // 运行中的任务属于当前安装实例，不从备份归档恢复。
        pending = current.pending, downloads = current.downloads, syncStatus = current.syncStatus
    )
    return merged
}
