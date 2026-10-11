package cc.novelia.app.files.exporting

import java.io.File
import java.io.OutputStream
import java.util.UUID

/**
 * 系统文件选择器打开期间，将待导出字节保留在磁盘，界面只保存可恢复的 UUID。
 * 这样 Activity 或进程重建后仍能完成导出，也不会把大文件塞进 Bundle。
 * finish 无论成功、失败或用户取消都会释放该载荷，失败重试需重新生成导出结果。
 */
internal class PendingExportFiles(private val directory: File) {
    private fun file(id: String): File {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "待导出文件标识无效，请重新处理文件" }
        return File(directory, "tool-export-$id.bin")
    }

    fun create(bytes: ByteArray, checkCancelled: () -> Unit = {}): String {
        val id = UUID.randomUUID().toString()
        val target = file(id)
        try {
            target.outputStream().use { output ->
                var offset = 0
                while (offset < bytes.size) {
                    checkCancelled()
                    val count = minOf(65536, bytes.size - offset)
                    output.write(bytes, offset, count)
                    offset += count
                }
                checkCancelled()
            }
            return id
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }

    /** 目标为空表示用户取消文件选择；每次结束的尝试都会释放暂存载荷。 */
    fun finish(id: String, destination: (() -> OutputStream?)?, checkCancelled: () -> Unit = {}) {
        val source = file(id)
        try {
            if (destination == null) return
            require(source.isFile) { "待导出结果已不存在，请重新处理文件后再导出" }
            source.inputStream().use { input ->
                val output = requireNotNull(destination()) { "无法写入所选文件，请选择其他位置" }
                output.use {
                    val buffer = ByteArray(65536)
                    while (true) {
                        checkCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        it.write(buffer, 0, count)
                    }
                }
            }
        } finally { source.delete() }
    }
}
