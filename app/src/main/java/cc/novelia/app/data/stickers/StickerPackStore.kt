package cc.novelia.app.data.stickers

import cc.novelia.app.data.storage.appJson
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * 素材、草稿和已安装贴纸均保存在本机文件目录，不依赖临时 URI 授权。
 * 图片使用不可变文件名，最后原子替换清单；失败的导入/编辑不会覆盖已有贴纸集。
 */
class StickerPackStore(private val root: File, private val imageType: (File) -> String = ::validateStickerImage) {
    private val mutableState = MutableStateFlow(StickerLibrary())
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    @Serializable private data class DraftRecord(val originalId: String?, val manifest: StickerPackManifest)

    private suspend fun <T> disk(block: suspend () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }

    suspend fun load() = disk {
        val errors = mutableListOf<String>()
        fun <T> readOrSkip(block: () -> T): T? = try { block() }
            catch(e: Exception) { errors += e.message ?: "文件无法读取"; null }
        val packs = File(root, "packs").listFiles().orEmpty().filter { it.isDirectory && LOCAL_STICKER_ID.matches(it.name) }
            .mapNotNull { directory -> readOrSkip {
                val manifest = readManifest(directory)
                require(manifest.id == directory.name) { "贴纸集 ID 与目录不一致" }
                LocalStickerPack(manifest, directory)
            } }
        val drafts = File(root, "drafts").listFiles().orEmpty().filter { it.isDirectory && STICKER_DRAFT_ID.matches(it.name) }
            .mapNotNull { directory -> readOrSkip { readDraft(directory.name) } }
        mutableState.value = StickerLibrary(packs.sortedBy { it.manifest.title }, drafts, ready = true,
            error = if(errors.isEmpty()) null else "有 ${errors.size} 个贴纸集或草稿无法读取：${errors.first()}")
    }

    suspend fun beginDraft(packId: String? = null): StickerDraft = disk {
        val original = packId?.let { id -> mutableState.value.packs.firstOrNull { it.id == id } ?: error("贴纸集已不存在") }
        val id = UUID.randomUUID().toString()
        val directory = draftDirectory(id).apply { check(mkdirs()) { "无法创建贴纸草稿" } }
        try {
            val manifest = if(original == null) StickerPackManifest(id = newPackId(), title = "未命名贴纸集", companionName = "动态icon")
                else {
                val copied = original.manifest.artwork.values.distinct().associateWith { path ->
                    val source = stickerFile(original.directory, path)
                    val name = UUID.randomUUID().toString() + "." + source.extension
                    source.inputStream().use { copyImage(it, File(directory, name)) }
                    name
                }
                original.manifest.copy(artwork = original.manifest.artwork.mapValues { (_, path) -> copied.getValue(path) })
            }
            val draft = StickerDraft(id, original?.id, LocalStickerPack(manifest, directory))
            writeDraft(draft)
            draft
        } catch(e: Throwable) { directory.deleteRecursively(); throw e }
    }

    suspend fun addImages(draftId: String, sources: List<Pair<StickerSlot, () -> InputStream>>): StickerDraft = disk {
        require(sources.isNotEmpty() && sources.size <= 13 && sources.map { it.first }.distinct().size == sources.size) { "图片对应的表情重复或数量超出限制" }
        val draft = readDraft(draftId)
        val created = mutableListOf<File>()
        try {
            val images = draft.pack.manifest.artwork.toMutableMap()
            sources.forEach { (slot, open) ->
                val temporary = File(draft.pack.directory, UUID.randomUUID().toString() + ".tmp").also { created += it }
                open().use { copyImage(it, temporary) }
                val extension = imageType(temporary)
                require(extension in listOf("png", "jpg", "webp")) { "不支持的图片格式" }
                val target = File(draft.pack.directory, UUID.randomUUID().toString() + "." + extension).also { created += it }
                check(temporary.renameTo(target)) { "无法保存贴纸图片" }
                images[slot.key] = target.name
            }
            require(images.values.distinct().sumOf { stickerFile(draft.pack.directory, it).length() } <= STICKER_PACK_LIMIT) { "一套贴纸的图片总大小不能超过 64 MB" }
            currentCoroutineContext().ensureActive()
            val updated = draft.copy(pack = draft.pack.copy(manifest = draft.pack.manifest.copy(artwork = images)))
            writeDraft(updated)
            // 只有新清单提交成功后才回收被替换的旧图片。
            draft.pack.manifest.artwork.values.filter { it !in images.values }.forEach { path -> runCatching { stickerFile(draft.pack.directory, path).delete() } }
            updated
        } catch(e: Throwable) { created.forEach { it.delete() }; throw e }
    }

    suspend fun removeImage(draftId: String, slot: StickerSlot): StickerDraft = disk {
        val draft = readDraft(draftId)
        val removed = draft.pack.manifest.artwork[slot.key]
        val updated = draft.copy(pack = draft.pack.copy(manifest = draft.pack.manifest.copy(artwork = draft.pack.manifest.artwork - slot.key)))
        writeDraft(updated)
        if(removed != null && removed !in updated.pack.manifest.artwork.values) runCatching { stickerFile(draft.pack.directory, removed).delete() }
        updated
    }

    suspend fun updateDraftDetails(draftId: String, title: String, companionName: String, heartColor: String) = disk {
        require(title.length <= 40 && companionName.length <= 24 && heartColor.length <= 7) { "草稿文字超出长度限制" }
        val draft = readDraft(draftId)
        writeDraft(draft.copy(pack = draft.pack.copy(manifest = draft.pack.manifest.copy(
            title = title, companionName = companionName, heartColor = heartColor))))
    }

    suspend fun saveDraft(draftId: String, title: String, companionName: String, heartColor: String): LocalStickerPack = disk {
        val draft = readDraft(draftId)
        val manifest = draft.pack.manifest.copy(title = title.trim(), companionName = companionName.trim().ifBlank { title.trim().take(24) }, heartColor = heartColor.uppercase())
        manifest.validate()
        require(draft.originalId == null || mutableState.value.packs.any { it.id == draft.originalId }) { "原贴纸集已删除，请重新制作" }
        val installed = install(manifest, draft.pack.directory)
        discardDraftFiles(draft)
        installed
    }

    suspend fun discardDraft(id: String) = disk { discardDraftFiles(readDraft(id)) }

    suspend fun remove(id: String) = disk {
        require(LOCAL_STICKER_ID.matches(id)) { "内置贴纸集不能删除" }
        val directory = packDirectory(id)
        val trash = stickerFile(root, "trash/${UUID.randomUUID()}")
        check(trash.parentFile!!.mkdirs() || trash.parentFile!!.isDirectory) { "无法准备删除贴纸集" }
        check(!directory.exists() || directory.renameTo(trash)) { "无法删除贴纸集，请重试" }
        mutableState.value = mutableState.value.copy(packs = mutableState.value.packs.filterNot { it.id == id })
        runCatching { trash.deleteRecursively() }
    }

    suspend fun importArchive(input: InputStream): LocalStickerPack = disk {
        val stage = stickerFile(root, "staging/${UUID.randomUUID()}").apply { check(mkdirs()) { "无法准备导入贴纸包" } }
        try {
            val files = linkedMapOf<String, File>()
            var total = 0L
            var count = 0
            ZipInputStream(input.buffered()).use { zip ->
                while(true) {
                    val entry = zip.nextEntry ?: break
                    require(++count <= 32) { "贴纸包文件数量超出限制" }
                    requireStickerPath(entry.name.removeSuffix("/"))
                    if(entry.isDirectory) {
                        require(zip.read() == -1) { "贴纸包目录条目包含异常数据" }
                        continue
                    }
                    require(entry.name !in files) { "贴纸包包含重复文件" }
                    val target = File(stage, "entry-${files.size}")
                    val limit = if(entry.name == "pack.json") STICKER_MANIFEST_LIMIT else STICKER_IMAGE_LIMIT
                    total += target.outputStream().use { copyLimited(zip, it, limit) }
                    require(total <= STICKER_PACK_LIMIT + STICKER_MANIFEST_LIMIT) { "贴纸包解压后不能超过 64 MB" }
                    files[entry.name] = target
                }
            }
            val metadata = requireNotNull(files["pack.json"]) { "贴纸包缺少 pack.json，请选择 Novelia 贴纸 ZIP 包" }
            var manifest = appJson.decodeFromString<StickerPackManifest>(metadata.readText(Charsets.UTF_8))
            manifest.validate()
            val imageFiles = manifest.artwork.values.distinct()
            require(files.keys == imageFiles.toSet() + "pack.json") { "贴纸包包含未声明或缺失的图片" }
            val mapped = imageFiles.associateWith { path ->
                val file = files[path] ?: error("贴纸包缺少图片：$path")
                val extension = imageType(file)
                require(extension in listOf("png", "jpg", "webp")) { "不支持的图片格式" }
                val renamed = File(stage, UUID.randomUUID().toString() + "." + extension)
                check(file.renameTo(renamed)) { "无法保存导入图片" }
                renamed.name
            }
            // 导入已有 ID 时制作独立副本，不覆盖用户在本机编辑过的版本。
            val id = manifest.id.takeIf { LOCAL_STICKER_ID.matches(it) && mutableState.value.packs.none { pack -> pack.id == it } && !packDirectory(it).exists() } ?: newPackId()
            manifest = manifest.copy(id = id, artwork = manifest.artwork.mapValues { (_, path) -> mapped.getValue(path) })
            currentCoroutineContext().ensureActive()
            install(manifest, stage)
        } finally { stage.deleteRecursively() }
    }

    suspend fun exportArchive(id: String, output: OutputStream) = disk {
        val pack = mutableState.value.packs.firstOrNull { it.id == id } ?: error("贴纸集已不存在")
        pack.manifest.validate()
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("pack.json"))
            zip.write(appJson.encodeToString(pack.manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            pack.manifest.artwork.values.distinct().forEach { path ->
                zip.putNextEntry(ZipEntry(path))
                stickerFile(pack.directory, path).inputStream().use { copyLimited(it, zip, STICKER_IMAGE_LIMIT) }
                zip.closeEntry()
            }
        }
    }

    private suspend fun install(manifest: StickerPackManifest, source: File): LocalStickerPack {
        manifest.validate()
        require(LOCAL_STICKER_ID.matches(manifest.id))
        val previous = mutableState.value.packs.firstOrNull { it.id == manifest.id }
        require(previous != null || mutableState.value.packs.size < 100) { "最多保存 100 套自定义贴纸集" }
        require(manifest.artwork.values.distinct().sumOf { stickerFile(source, it).length() } <= STICKER_PACK_LIMIT) { "一套贴纸的图片总大小不能超过 64 MB" }
        val directory = packDirectory(manifest.id)
        require(previous != null || !directory.exists()) { "贴纸集目录已存在，请重新导入为副本" }
        check(directory.mkdirs() || directory.isDirectory) { "无法保存贴纸集" }
        val created = mutableListOf<File>()
        try {
            val paths = manifest.artwork.values.distinct().associateWith { path ->
                val original = stickerFile(source, path)
                val target = File(directory, UUID.randomUUID().toString() + "." + original.extension).also { created += it }
                original.inputStream().use { copyImage(it, target) }
                target.name
            }
            val installedManifest = manifest.copy(artwork = manifest.artwork.mapValues { (_, path) -> paths.getValue(path) })
            currentCoroutineContext().ensureActive()
            writeAtomic(File(directory, "pack.json"), appJson.encodeToString(installedManifest))
            val installed = LocalStickerPack(installedManifest, directory)
            mutableState.value = mutableState.value.copy(packs = (mutableState.value.packs.filterNot { it.id == installed.id } + installed).sortedBy { it.manifest.title })
            previous?.manifest?.artwork?.values?.distinct()?.forEach { path -> runCatching { stickerFile(directory, path).delete() } }
            return installed
        } catch(e: Throwable) {
            created.forEach { it.delete() }
            if(previous == null) directory.deleteRecursively()
            throw e
        }
    }

    private fun readManifest(directory: File): StickerPackManifest = readText(File(directory, "pack.json")).let {
        appJson.decodeFromString<StickerPackManifest>(it).also { manifest ->
            manifest.validate()
            require(manifest.artwork.values.distinct().sumOf { path ->
                val file = stickerFile(directory, path)
                require(file.isFile && file.length() in 1..STICKER_IMAGE_LIMIT) { "贴纸图片缺失或大小无效" }
                file.length()
            } <= STICKER_PACK_LIMIT) { "贴纸集超过 64 MB" }
        }
    }

    private fun readDraft(id: String): StickerDraft {
        val directory = draftDirectory(id)
        val record = appJson.decodeFromString<DraftRecord>(readText(File(directory, "draft.json")))
        // 草稿允许名称留空、颜色输入未完成；正式保存时才检查完整的显示信息。
        require(record.manifest.title.length <= 40 && record.manifest.companionName.length <= 24 && record.manifest.heartColor.length <= 7)
        record.manifest.copy(title = "草稿", companionName = "动态icon", heartColor = "#E15C86").validate(requireNeutral = false)
        require(LOCAL_STICKER_ID.matches(record.manifest.id) && (record.originalId == null || record.originalId == record.manifest.id)) { "草稿 ID 无效" }
        require(record.manifest.artwork.values.all { stickerFile(directory, it).isFile }) { "草稿图片已丢失" }
        return StickerDraft(id, record.originalId, LocalStickerPack(record.manifest, directory))
    }

    private fun writeDraft(draft: StickerDraft) {
        writeAtomic(File(draft.pack.directory, "draft.json"), appJson.encodeToString(DraftRecord(draft.originalId, draft.pack.manifest)))
        mutableState.value = mutableState.value.copy(drafts = mutableState.value.drafts.filterNot { it.id == draft.id } + draft)
    }

    private fun discardDraftFiles(draft: StickerDraft) {
        // 已提交贴纸集后，草稿清理失败也不回滚成功保存的结果。
        runCatching { draftDirectory(draft.id).deleteRecursively() }
        mutableState.value = mutableState.value.copy(drafts = mutableState.value.drafts.filterNot { it.id == draft.id })
    }

    private fun packDirectory(id: String): File {
        require(LOCAL_STICKER_ID.matches(id)) { "无效的贴纸集 ID" }
        return stickerFile(root, "packs/$id")
    }
    private fun draftDirectory(id: String): File {
        require(STICKER_DRAFT_ID.matches(id)) { "无效的草稿 ID" }
        return stickerFile(root, "drafts/$id")
    }
    private fun newPackId() = "local-${UUID.randomUUID()}"
    private fun readText(file: File): String {
        require(file.isFile && file.length() <= STICKER_MANIFEST_LIMIT) { "贴纸清单缺失或过大" }
        return file.readText(Charsets.UTF_8)
    }

    private suspend fun copyImage(input: InputStream, file: File) {
        val size = FileOutputStream(file).use { output -> copyLimited(input, output, STICKER_IMAGE_LIMIT).also { output.fd.sync() } }
        require(size > 0) { "图片文件为空" }
    }

    private suspend fun copyLimited(input: InputStream, output: OutputStream, limit: Long): Long {
        val buffer = ByteArray(8192)
        var size = 0L
        while(true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if(count < 0) return size
            size += count
            require(size <= limit) { if(limit == STICKER_IMAGE_LIMIT) "单张贴纸不能超过 8 MB" else "贴纸包清单过大" }
            output.write(buffer, 0, count)
        }
    }

    private fun writeAtomic(file: File, text: String) {
        val temporary = File(file.parentFile, ".${file.name}-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch(_: AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { temporary.delete() }
    }
}
