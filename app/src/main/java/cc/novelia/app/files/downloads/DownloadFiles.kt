package cc.novelia.app.files.downloads

import cc.novelia.app.data.model.DownloadEntry
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private const val MAX_FILENAME_BYTES = 255
private val unsafeFilenameChars = Regex("""[\\/:*?"<>|\p{Cntrl}]""")

/** Android 的单个文件名按字节计长，预算包含任务 ID，并为扩展名留出空间。 */
internal fun downloadFileName(id: String, label: String): String {
    val name = "$id-${label.replace(unsafeFilenameChars, "_")}"
    if (name.toByteArray().size <= MAX_FILENAME_BYTES) return name
    val extension = name.substringAfterLast('.', "").takeIf { it.length in 1..10 && it.all(Char::isLetterOrDigit) }
        ?.let { ".$it" }.orEmpty()
    val stem = name.removeSuffix(extension)
    val budget = MAX_FILENAME_BYTES - extension.toByteArray().size
    var end = minOf(stem.length, budget)
    while (stem.substring(0, end).toByteArray().size > budget) end--
    if (end > 0 && stem[end - 1].isHighSurrogate()) end--
    return stem.take(end) + extension
}

/** 旧任务重试时也修正下载名，原站的作品、分卷和翻译参数保持原值。 */
internal fun DownloadEntry.withUsableFileName(): DownloadEntry {
    if (fileName.toByteArray().size <= MAX_FILENAME_BYTES && !unsafeFilenameChars.containsMatchIn(fileName)) return this
    val name = downloadFileName(id, fileName.removePrefix("$id-"))
    val source = url.toHttpUrlOrNull()
    val requestUrl = if (source?.queryParameter("filename") != null)
        source.newBuilder().setQueryParameter("filename", name.removePrefix("$id-")).build().toString() else url
    return copy(fileName = name, url = requestUrl)
}

/**
 * 协调下载临时文件与生命周期命令。暂停/删除的取消指令返回时，旧 Worker 可能仍在退出，
 * 因此 active 集合保护仍在使用的 .part 文件，任务锁串行化取消、替换与成品发布。
 * 每轮 Worker 使用独立临时文件，避免新旧下载同时写入同一路径。
 */
internal object DownloadFiles {
    private class TaskLock(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val taskLocks = mutableMapOf<String, TaskLock>()
    private val active = mutableSetOf<String>()
    private val safeId = Regex("[a-zA-Z0-9-]+")
    private val partialName = Regex("[a-zA-Z0-9-]+\\.part")

    /** 串行处理生命周期命令和成品文件发布，无需等待旧 Worker 完全退出。 */
    suspend fun <T> withTaskLock(directory: File, downloadId: String, block: suspend () -> T): T {
        require(safeId.matches(downloadId))
        val key = File(directory, downloadId).absolutePath
        val lock = synchronized(taskLocks) { taskLocks.getOrPut(key) { TaskLock() }.also { it.users++ } }
        try { return lock.mutex.withLock { block() } }
        finally { synchronized(taskLocks) { if (--lock.users == 0) taskLocks.remove(key) } }
    }

    /** 取得任务锁后再验证所有权，通过同目录移动发布完整文件，随后才更新完成状态。 */
    suspend fun commit(directory: File, downloadId: String, partial: File, destination: File,
        isCurrent: () -> Boolean, completed: () -> Unit): Boolean = withTaskLock(directory, downloadId) {
        if (!isCurrent()) return@withTaskLock false
        // 两个路径位于同一私有目录，通过移动发布完整文件，避免出现部分拷贝。
        try {
            Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            throw DownloadStorageException(e)
        }
        completed()
        true
    }

    @Synchronized fun acquire(directory: File, downloadId: String, workId: String): File {
        require(safeId.matches(downloadId) && safeId.matches(workId))
        return File(directory, "$downloadId-$workId.part").also { active += it.absolutePath }
    }

    @Synchronized fun release(file: File) {
        file.delete()
        active -= file.absolutePath
    }

    /** 旧记录可能指向从未能创建的文件名。只删除下载目录的直接子项。 */
    fun deleteFinalFile(directory: File, name: String) {
        val file = File(directory, name)
        if (file.name != name || name.isEmpty() || name == "." || name == ".." ||
            '\u0000' in name || name.toByteArray().size > MAX_FILENAME_BYTES) return
        try {
            Files.deleteIfExists(file.toPath())
        } catch (e: IOException) {
            throw DownloadStorageException(e, "无法删除下载文件，请检查设备存储后重试")
        }
    }

    /** 只清理符合临时文件命名规则且未被运行任务占用的文件；不触碰下载成品。 */
    @Synchronized fun cleanup(directory: File, downloadId: String? = null) {
        if (downloadId != null) require(safeId.matches(downloadId))
        directory.listFiles().orEmpty().filter { file ->
            file.isFile && partialName.matches(file.name) && file.absolutePath !in active &&
                (downloadId == null || file.name == "$downloadId.part" || file.name.startsWith("$downloadId-"))
        }.forEach { it.delete() }
    }
}
