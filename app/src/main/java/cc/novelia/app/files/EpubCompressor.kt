package cc.novelia.app.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object EpubCompressor {
    fun compress(bytes: ByteArray): ByteArray {
        val files = DocumentTools.unzip(bytes)
        require(files.containsKey("META-INF/container.xml")) { "不是有效的 EPUB 文件" }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            val mime = files.remove("mimetype") ?: "application/epub+zip".toByteArray()
            val entry = ZipEntry("mimetype").apply { method = ZipEntry.STORED; size = mime.size.toLong(); compressedSize = size; crc = CRC32().apply { update(mime) }.value }
            zip.putNextEntry(entry); zip.write(mime); zip.closeEntry()
            for((name, content) in files) {
                val ext = name.substringAfterLast('.').lowercase()
                var optimized = content
                if(ext in listOf("jpg", "jpeg", "png", "webp")) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(content, 0, content.size, bounds)
                    if(bounds.outWidth > 0 && bounds.outHeight > 0) {
                        var scale = 1; while(maxOf(bounds.outWidth, bounds.outHeight) / scale > 2000) scale *= 2
                        val bitmap = BitmapFactory.decodeByteArray(content, 0, content.size, BitmapFactory.Options().apply { inSampleSize = scale })
                        bitmap?.let {
                            val target = ByteArrayOutputStream()
                            @Suppress("DEPRECATION") val format = when(ext) { "png" -> Bitmap.CompressFormat.PNG; "webp" -> Bitmap.CompressFormat.WEBP; else -> Bitmap.CompressFormat.JPEG }
                            it.compress(format, 82, target); it.recycle()
                            if(target.size() < content.size) optimized = target.toByteArray()
                        }
                    }
                }
                zip.putNextEntry(ZipEntry(name)); zip.write(optimized); zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
