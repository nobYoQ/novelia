package cc.novelia.app.files.downloads

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
        Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
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

    /** 只清理符合临时文件命名规则且未被运行任务占用的文件；不触碰下载成品。 */
    @Synchronized fun cleanup(directory: File, downloadId: String? = null) {
        if (downloadId != null) require(safeId.matches(downloadId))
        directory.listFiles().orEmpty().filter { file ->
            file.isFile && partialName.matches(file.name) && file.absolutePath !in active &&
                (downloadId == null || file.name == "$downloadId.part" || file.name.startsWith("$downloadId-"))
        }.forEach { it.delete() }
    }
}
