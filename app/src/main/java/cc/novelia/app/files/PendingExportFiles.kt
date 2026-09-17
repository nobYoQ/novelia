package cc.novelia.app.files

import java.io.File
import java.io.OutputStream
import java.util.UUID

/** The document picker retains only an ID; its payload survives activity/process recreation on disk. */
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

    /** A null destination means the user cancelled the picker. Every finished attempt releases its payload. */
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
