package cc.novelia.app.data.images

import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.storage.hashName
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal data class CustomBookCover(val file: File, val revision: Long)

/** 封面按书目身份保存在应用私有目录，文件本身是持久化记录。 */
internal class BookCoverStore(
    private val directory: File,
    private val cache: File,
    private val validate: (File) -> Unit = { validateRasterImage(it, 8192) },
) {
    private val revisions = MutableStateFlow<Map<String, Long>>(emptyMap())

    fun observe(ref: BookRef): Flow<CustomBookCover?> {
        val key = hashName(ref.key)
        val file = File(directory, key)
        return revisions.map { it[key] ?: 0L }.distinctUntilChanged()
            .map { revision -> if(file.isFile) CustomBookCover(file, revision) else null }
            .flowOn(Dispatchers.IO)
    }

    suspend fun replace(ref: BookRef, open: () -> InputStream) = withContext(Dispatchers.IO) {
        val key = hashName(ref.key)
        val revision = revisions.value[key] ?: 0L
        cache.mkdirs()
        val temporary = File.createTempFile("cover-", ".tmp", cache)
        try {
            open().use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while(true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if(count < 0) break
                        total += count
                        require(total <= 16L * 1024 * 1024) { "封面图片不能超过 16 MB" }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            validate(temporary)
            currentCoroutineContext().ensureActive()
            synchronized(this@BookCoverStore) {
                check((revisions.value[key] ?: 0L) == revision) { "封面已变更，请重新选择图片" }
                directory.mkdirs()
                Files.move(temporary.toPath(), File(directory, key).toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                revisions.value += key to (revision + 1)
            }
        } finally {
            temporary.delete()
        }
    }

    @Synchronized fun restoreDefault(ref: BookRef) {
        val key = hashName(ref.key)
        Files.deleteIfExists(File(directory, key).toPath())
        revisions.value += key to ((revisions.value[key] ?: 0L) + 1)
    }
}
