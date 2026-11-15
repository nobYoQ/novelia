package cc.novelia.app.data.model

import kotlinx.serialization.Serializable

/** 方案按值应用，单书、夜间或电子纸的后续编辑各自保存在原有作用域。 */
@Serializable
data class ReaderThemePalette(val colors: ReaderCustomColors, val toolbarTransparency: Float) {
    fun applyTo(settings: ReaderSettings) = settings.withTheme("custom").copy(
        customColors = colors, toolbarTransparency = toolbarTransparency,
    )

    companion object {
        const val MAX_NAME = 40

        fun from(settings: ReaderSettings) =
            ReaderThemePalette(settings.resolvedCustomColors, settings.resolvedToolbarTransparency)
    }
}

internal fun Map<String, ReaderThemePalette>.requireValidReaderThemes() {
    require(all { (name, palette) ->
        name.isNotBlank() && name == name.trim() && name.length <= ReaderThemePalette.MAX_NAME &&
            palette.colors.valid && palette.toolbarTransparency in 0f..1f
    }) { "阅读主题方案无效" }
}
