package cc.novelia.app.data.theme

import android.graphics.BitmapFactory
import cc.novelia.app.data.model.ThemePreset
import cc.novelia.app.data.model.ThemePresets
import cc.novelia.app.data.storage.LocalStore
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 编辑时只写临时图片。保存时先安装图片再发布偏好，写盘成功才回收失去最后一个引用的旧图。 */
internal class CustomThemeStore(private val library: LocalStore) {
    private val directory = File(library.context.filesDir, "theme-images")
    private val drafts = File(library.context.cacheDir, "theme-drafts")
    private val mutex = Mutex()

    fun imageFile(hash: String): File {
        require(ThemePreset.imagePattern.matches(hash))
        return File(directory, hash)
    }

    private fun draftDirectory(session: String): File {
        require(ThemePreset.idPattern.matches(session))
        return File(drafts, session)
    }

    fun draftFile(session: String, hash: String): File {
        require(ThemePreset.imagePattern.matches(hash))
        return File(draftDirectory(session), hash)
    }

    suspend fun pickImage(session: String, open: () -> InputStream): String = withContext(Dispatchers.IO) {
        val folder = draftDirectory(session).apply { mkdirs() }
        val temporary = File.createTempFile("pick-", ".tmp", folder)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            open().use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var size = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        require(size <= MAX_THEME_IMAGE_BYTES) { "背景图片不能超过 16 MB" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            validateThemeImage(temporary)
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            Files.move(temporary.toPath(), draftFile(session, hash).toPath(), StandardCopyOption.REPLACE_EXISTING)
            hash
        } finally {
            temporary.delete()
        }
    }

    suspend fun discardDraft(session: String) = withContext(Dispatchers.IO) { draftDirectory(session).deleteRecursively() }

    /** 普通设置仅含参数。在没有对应本机图片的设备上使用该主题的纯色背景。 */
    fun withAvailableImages(presets: ThemePresets): ThemePresets {
        presets.requireValid()
        return presets.copy(items = presets.items.map { preset ->
            if (preset.image != null && !imageFile(preset.image).isFile) preset.copy(image = null) else preset
        })
    }

    suspend fun <T> change(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            val before = library.state.value.themePresets
            val result = block()
            val after = library.state.value.themePresets
            if (before != after) withContext(NonCancellable) {
                library.flush()
                (before.images - after.images).forEach { imageFile(it).delete() }
            }
            result
        }
    }

    suspend fun save(preset: ThemePreset, session: String) = change {
        require(preset.valid) { "请检查主题名称、配色和背景设置" }
        check(library.recoveryIssue.value == null) { "请先恢复受保护的本地资料" }
        val installed = preset.image?.let { hash -> install(hash, draftFile(session, hash)) }
        try {
            library.update { it.copy(themePresets = it.themePresets.saveAndSelect(preset)) }
        } finally {
            if (installed != null && installed.name !in library.state.value.themePresets.images) installed.delete()
        }
    }

    suspend fun delete(id: String) = change { library.update { it.copy(themePresets = it.themePresets.remove(id)) } }

    /** 调用者持有 change 的锁。相同内容共用一个文件，失败的复制不会发布半张图片。 */
    fun install(hash: String, source: File): File? {
        val target = imageFile(hash)
        if (target.isFile) return null
        require(source.isFile) { "背景图片已丢失，请重新选择" }
        directory.mkdirs()
        val temporary = File.createTempFile("install-", ".tmp", directory)
        try {
            source.inputStream().use { input ->
                temporary.outputStream().use { output -> input.copyTo(output); output.fd.sync() }
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return target
        } finally {
            temporary.delete()
        }
    }
}

internal const val MAX_THEME_IMAGE_BYTES = 16L * 1024 * 1024

internal fun validateThemeImage(file: File) {
    require(file.length() in 1..MAX_THEME_IMAGE_BYTES) { "背景图片为空或超过 16 MB" }
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, options)
    require(options.outMimeType in setOf("image/jpeg", "image/png", "image/webp") &&
        options.outWidth in 1..16384 && options.outHeight in 1..16384 &&
        options.outWidth.toLong() * options.outHeight <= 100_000_000L) { "请选择有效的 JPG、PNG 或 WebP 图片（不超过 1 亿像素，单边不超过 16384 像素）" }
    options.inSampleSize = Integer.highestOneBit((maxOf(options.outWidth, options.outHeight) / 128).coerceAtLeast(1))
    options.inJustDecodeBounds = false
    val probe = requireNotNull(BitmapFactory.decodeFile(file.path, options)) { "无法解码背景图片" }
    probe.recycle()
}
