package cc.novelia.app.sharing

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal fun CardArtwork.writePng(page: Int, qr: Boolean, output: OutputStream) {
    val bitmap = render(page, qr)
    try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "图片生成失败" } }
    finally { bitmap.recycle() }
}

internal suspend fun shareImages(context: Context, artwork: CardArtwork, card: ShareCard, qr: Boolean): Intent {
    val dir = File(context.cacheDir, "share-cards").apply { mkdirs() }
    val expiry = System.currentTimeMillis() - 48 * 60 * 60 * 1000L
    dir.listFiles()?.filter { it.isFile && it.lastModified() < expiry }?.forEach { it.delete() }
    val files = mutableListOf<File>()
    val uris = ArrayList<Uri>()
    try {
        repeat(artwork.pageCount) { page ->
            currentCoroutineContext().ensureActive()
            val file = File.createTempFile("novelia-${page + 1}-", ".png", dir)
            files.add(file)
            file.outputStream().buffered().use { artwork.writePng(page, qr, it) }
            uris.add(FileProvider.getUriForFile(context, "${context.packageName}.files", file))
        }
    } catch (e: Exception) { files.forEach { it.delete() }; throw e }
    val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    send.type = "image/png"
    send.clipData = ClipData.newUri(context.contentResolver, "Novelia", uris.first()).apply {
        uris.drop(1).forEach { addItem(ClipData.Item(it)) }
    }
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    card.url?.let { send.putExtra(Intent.EXTRA_TEXT, "${card.title}\n$it") }
    return Intent.createChooser(send, "分享图片")
}

@RequiresApi(Build.VERSION_CODES.Q)
internal suspend fun saveCardsToAlbum(context: Context, artwork: CardArtwork, qr: Boolean) {
    val resolver = context.contentResolver
    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val pending = mutableListOf<Uri>()
    val time = System.currentTimeMillis()
    try {
        repeat(artwork.pageCount) { page ->
            currentCoroutineContext().ensureActive()
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "Novelia-$time-${page + 1}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Novelia")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = requireNotNull(resolver.insert(collection, values)) { "无法创建图片" }
            pending.add(uri)
            requireNotNull(resolver.openOutputStream(uri)).buffered().use { artwork.writePng(page, qr, it) }
        }
        val published = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
        pending.forEach { check(resolver.update(it, published, null, null) == 1) { "保存图片失败" } }
    } catch (e: Exception) {
        pending.forEach { runCatching { resolver.delete(it, null, null) } }
        throw e
    }
}
