package cc.novelia.app.files

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Active files are protected while cancelled workers wind down. */
internal object DownloadFiles {
    private class TaskLock(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val taskLocks = mutableMapOf<String, TaskLock>()
    private val active = mutableSetOf<String>()
    private val safeId = Regex("[a-zA-Z0-9-]+")
    private val partialName = Regex("[a-zA-Z0-9-]+\\.part")

    /** Serialize lifecycle commands and file promotion, without waiting for a worker to terminate. */
    suspend fun <T> withTaskLock(directory: File, downloadId: String, block: suspend () -> T): T {
        require(safeId.matches(downloadId))
        val key = File(directory, downloadId).absolutePath
        val lock = synchronized(taskLocks) { taskLocks.getOrPut(key) { TaskLock() }.also { it.users++ } }
        try { return lock.mutex.withLock { block() } }
        finally { synchronized(taskLocks) { if (--lock.users == 0) taskLocks.remove(key) } }
    }

    suspend fun commit(directory: File, downloadId: String, partial: File, destination: File,
        isCurrent: () -> Boolean, completed: () -> Unit): Boolean = withTaskLock(directory, downloadId) {
        if (!isCurrent()) return@withTaskLock false
        // Both paths are in the same private directory; move instead of publishing a partial copy.
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

    @Synchronized fun cleanup(directory: File, downloadId: String? = null) {
        if (downloadId != null) require(safeId.matches(downloadId))
        directory.listFiles().orEmpty().filter { file ->
            file.isFile && partialName.matches(file.name) && file.absolutePath !in active &&
                (downloadId == null || file.name == "$downloadId.part" || file.name.startsWith("$downloadId-"))
        }.forEach { it.delete() }
    }
}
