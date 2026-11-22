package cc.novelia.app.sharing

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import cc.novelia.app.data.catalog.BookLinks
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.IOException
import kotlinx.coroutines.CancellationException

internal fun cardQr(url: String): BitMatrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 0, 0,
    mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4))

internal fun readCardLink(width: Int, height: Int, pixels: IntArray): String? {
    val source = RGBLuminanceSource(width, height, pixels)
    fun scan(x: Int, y: Int, w: Int, h: Int): String? = try {
        val result = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source.crop(x, y, w, h))),
            mapOf(DecodeHintType.TRY_HARDER to true)).text
        result.takeIf { BookLinks.parse(it) != null }
    } catch (_: ReaderException) { null }
    return scan(0, 0, width, height) ?: if (width >= 2 && height >= 2)
        scan(width / 2, height / 2, width - width / 2, height - height / 2) else null
}

internal fun readCardLink(resolver: ContentResolver, uri: Uri): String? {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val w = bounds.outWidth.toLong()
        val h = bounds.outHeight.toLong()
        if (w <= 0 || h <= 0) return null
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = 1
            while (maxOf(w, h) / inSampleSize > 8192 || (w / inSampleSize) * (h / inSampleSize) > 4_000_000L)
                inSampleSize *= 2
        }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            readCardLink(bitmap.width, bitmap.height, pixels)
        } finally { bitmap.recycle() }
    } catch (e: CancellationException) { throw e }
    catch (_: IOException) { null }
    catch (_: RuntimeException) { null } // 外部文档提供器可能已经撤销授权或在两次打开之间移除了文件。
}
