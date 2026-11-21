package cc.novelia.app.files.comic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import cc.novelia.app.reader.ComicPage
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

internal suspend fun encodeComicPageCover(page: ComicPage): ByteArray = runInterruptible(Dispatchers.IO) {
    require(page.text == null) { "请选择图片页作为封面" }
    val file = page.load?.invoke() ?: page.file
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    val length = maxOf(bounds.outWidth, bounds.outHeight)
    var sample = 1
    while (length / sample > 1600) sample *= 2
    val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: throw IOException("无法读取所选漫画页")
    try {
        val matrix = comicImageOrientation(page.orientation)
        val cover = if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        try {
            val output = ByteArrayOutputStream()
            val format = if (cover.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            check(cover.compress(format, 90, output)) { "无法保存所选漫画页" }
            output.toByteArray()
        } finally { if (cover !== bitmap) cover.recycle() }
    } finally { bitmap.recycle() }
}

internal fun comicImageOrientation(orientation: Int): Matrix = Matrix().apply {
    if (orientation in listOf(2, 4, 5, 7)) setScale(-1f, 1f)
    postRotate(when (orientation) { 3, 4 -> 180f; 5, 8 -> 270f; 6, 7 -> 90f; else -> 0f })
}
