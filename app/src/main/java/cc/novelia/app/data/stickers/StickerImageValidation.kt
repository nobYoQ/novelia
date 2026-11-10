package cc.novelia.app.data.stickers

import android.graphics.BitmapFactory
import java.io.File

/** 先检查尺寸，再按缩略图解码确认文件可用，避免为校验加载完整大图。 */
internal fun validateStickerImage(file: File): String {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096) { "图片尺寸需在 1–4096 像素之间" }
    val extension = when(bounds.outMimeType) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/jpeg" -> "jpg"
        else -> throw IllegalArgumentException("请选择 PNG、WebP 或 JPG 图片")
    }
    val options = BitmapFactory.Options().apply {
        var sample = 1
        while(bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
        inSampleSize = sample
    }
    val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
        ?: throw IllegalArgumentException("图片已损坏或无法解码")
    bitmap.recycle()
    return extension
}
