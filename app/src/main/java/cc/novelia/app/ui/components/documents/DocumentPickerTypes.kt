package cc.novelia.app.ui.components.documents

/** 默认只声明可处理的类型；识别不准确的文件由单独的“显示所有文件”入口选择。 */
internal object DocumentPickerTypes {
    val Epub = listOf("application/epub+zip")
    val Volumes = Epub + "text/plain"
    private val subtitles = listOf("application/x-subrip", "application/srt", "text/srt")
    val Books = Volumes + subtitles
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
