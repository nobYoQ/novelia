package cc.novelia.app.files.exporting

import cc.novelia.app.data.model.DownloadEntry
import cc.novelia.app.data.model.BookCard
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import cc.novelia.app.files.downloads.DownloadFiles

/** 流式打包原始下载文件；每个文件的读取与任务删除、成品发布共用任务锁。 */
internal suspend fun writeDownloadArchive(
    directory: File, entries: List<DownloadEntry>, destination: OutputStream,
    books: List<BookCard> = emptyList(),
    isCurrent: (DownloadEntry) -> Boolean = { true }, checkCancelled: () -> Unit = {},
) {
    require(entries.isNotEmpty() && entries.all { it.status == "已完成" } && entries.map { it.id }.distinct().size == entries.size) {
        "请选择已完成的下载文件"
    }
    checkCancelled()
    val paths = downloadArchivePaths(entries, books)
    ZipOutputStream(destination, Charsets.UTF_8).use { zip ->
        zip.setLevel(Deflater.BEST_SPEED)
        val buffer = ByteArray(65536)
        entries.forEach { entry ->
            checkCancelled()
            DownloadFiles.withTaskLock(directory, entry.id) {
                require(isCurrent(entry)) { "下载任务已变化，请重新选择：${entry.title}" }
                val file = File(directory, entry.fileName)
                require(file.name == entry.fileName && file.canonicalFile.parentFile == directory.canonicalFile && file.isFile) {
                    "下载文件已不存在，请重新下载：${entry.title}"
                }
                zip.putNextEntry(ZipEntry(paths.getValue(entry.id)).apply { time = file.lastModified() })
                file.inputStream().use { input ->
                    while(true) {
                        checkCancelled()
                        val count = input.read(buffer)
                        if(count < 0) break
                        zip.write(buffer, 0, count)
                    }
                }
                require(isCurrent(entry)) { "下载任务已变化，请重新选择：${entry.title}" }
                zip.closeEntry()
            }
        }
        checkCancelled()
    }
}

/** 导出暂存只保存 UUID，支持系统文件选择器期间重建；分享文件另行保留供接收应用读取。 */
internal class BookExportFiles(private val directory: File) {
    private fun file(id: String, sharing: Boolean = false, extension: String = "zip"): File {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "压缩包标识无效，请重新打包" }
        require(extension in setOf("zip", "epub", "txt", "srt")) { "不支持的文件类型" }
        return File(directory, "download-${if(sharing) "share" else "export"}-$id.$extension")
    }

    suspend fun create(downloads: File, entries: List<DownloadEntry>, books: List<BookCard> = emptyList(),
        isCurrent: (DownloadEntry) -> Boolean = { true }): String = create { output ->
        val work = kotlinx.coroutines.currentCoroutineContext()
        writeDownloadArchive(downloads, entries, output, books, isCurrent) { work.ensureActive() }
    }

    suspend fun create(write: suspend (OutputStream) -> Unit): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val target = file(id)
        try {
            target.outputStream().buffered().use { output -> write(output) }
            id
        } catch(error: Exception) { target.delete(); throw error }
    }

    fun finish(id: String, destination: (() -> OutputStream?)?, checkCancelled: () -> Unit = {}) {
        val source = file(id)
        try {
            if(destination == null) return
            require(source.isFile) { "压缩包已不存在，请重新打包" }
            source.inputStream().use { input ->
                requireNotNull(destination()) { "无法写入所选文件" }.use { output ->
                    val buffer = ByteArray(65536)
                    while(true) {
                        checkCancelled()
                        val count = input.read(buffer)
                        if(count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
        } finally { source.delete() }
    }

    fun share(id: String, extension: String = "zip"): File {
        val source = file(id)
        val shared = file(id, sharing = true, extension = extension)
        require(source.isFile && source.renameTo(shared)) { "压缩包已不存在，请重新打包" }
        // 只清理过期的分享包，不影响等待文件选择器的导出包或其他导出文件。
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        directory.listFiles().orEmpty().filter { it.name.matches(Regex("download-share-[0-9a-f-]{36}\\.(zip|epub|txt|srt)")) && it.lastModified() < cutoff }
            .forEach { it.delete() }
        return shared
    }
}
