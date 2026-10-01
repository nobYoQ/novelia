package cc.novelia.app.files

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** 只移除已停用工具的私有模型，不跟随任何符号链接。 */
internal fun removeRetiredModels(noBackupDirectory: File) {
    val target = noBackupDirectory.toPath().toAbsolutePath().normalize().resolve("ocr-models")
    if (Files.notExists(target, LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(target, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }
        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            if (exc != null) throw exc
            Files.delete(dir)
            return FileVisitResult.CONTINUE
        }
    })
}
