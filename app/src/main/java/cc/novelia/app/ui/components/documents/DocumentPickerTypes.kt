package cc.novelia.app.ui.components.documents

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts

/** 文件提供方经常为同一种漫画归档报告不同 MIME；书架在拿到 URI 后按文件名和实际内容校验。 */
internal class OpenBookDocuments : ActivityResultContract<Unit, List<Uri>>() {
    override fun createIntent(context: Context, input: Unit) = Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType("*/*")
        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> =
        ActivityResultContracts.OpenMultipleDocuments().parseResult(resultCode, intent)
}

/** 默认只声明可处理的类型；识别不准确的文件由单独的“显示所有文件”入口选择。 */
internal object DocumentPickerTypes {
    val Epub = listOf("application/epub+zip")
    val Volumes = Epub + "text/plain"
    private val subtitles = listOf("application/x-subrip", "application/srt", "text/srt")

    val Json = listOf("application/json", "text/json")
    val StickerImages = listOf("image/png", "image/webp", "image/jpeg")
    val Backup = listOf("application/zip", "application/x-zip-compressed", "application/x-zip")
    val Text = listOf("text/*", "application/json", "application/xml") + subtitles
    // 包含 Android 新旧版本和文件提供方使用的 SFNT / TrueType 类型名，不放行 WOFF。
    val Fonts = listOf(
        "font/ttf", "font/otf", "font/collection", "font/sfnt", "application/font-sfnt",
        "application/x-font-ttf", "application/x-font-otf", "application/x-font-truetype",
        "application/x-font-opentype", "application/vnd.ms-opentype", "application/x-truetype-font",
    )
}
