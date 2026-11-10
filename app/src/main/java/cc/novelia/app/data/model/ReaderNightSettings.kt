package cc.novelia.app.data.model

import kotlinx.serialization.Serializable
import kotlin.math.ln

/** 窗口亮度策略；传感器读数和当前计算亮度仅属于前台会话，不写入设置。 */
@Serializable data class ReaderBrightness(
    val mode: String = "system",
    val level: Float = .5f,
    val minimum: Float = .05f,
    val maximum: Float = 1f,
    val adjustment: Float = 0f
) {
    val valid get() = mode in MODES && level in .01f..1f && minimum in .01f..1f &&
        maximum in minimum..1f && adjustment in -0.5f..0.5f

    fun target(lux: Float?): Float = when (mode) {
        "system" -> -1f
        "adaptive" -> if (lux == null || !lux.isFinite() || lux < 0) level
            else (minimum + (maximum - minimum) * (ln(1f + lux) / ln(501f)).coerceIn(0f, 1f) + adjustment)
                .coerceIn(minimum, maximum)
        else -> level
    }

    companion object {
        val MODES = listOf("system", "manual", "adaptive")
        fun night() = ReaderBrightness(mode = "adaptive", level = .1f, maximum = .25f)
    }
}

/** 夜间覆盖项。空值继承普通配置；内置行距在普通配置上增加 0.1 倍字号。 */
@Serializable data class ReaderNightPreset(
    val theme: String = "custom",
    val customColors: ReaderCustomColors = ReaderCustomColors(0xB8B4AB, 0x1C1D1B, 0x252622),
    val fontSize: Float? = null,
    val lineHeight: Float? = null,
    val paragraphSpacing: Float? = null,
    val letterSpacing: Float = .02f,
    val weight: Boolean = false,
    val fontFamily: String? = null,
    val customFont: ReaderCustomFont? = null,
    val secondaryAlpha: Float = .85f,
    val toolbarTransparency: Float = 0f
) {
    val valid get() = theme in ReaderSettings.THEMES && customColors.valid &&
        (fontSize == null || fontSize in 14f..32f) && (lineHeight == null || lineHeight in ReaderSettings.MIN_LINE_HEIGHT..4f) &&
        (paragraphSpacing == null || paragraphSpacing in 0f..32f) && letterSpacing in 0f..0.15f &&
        (fontFamily == null || fontFamily in READER_FONT_FAMILIES) && (customFont == null || customFont.valid) &&
        (fontFamily != "custom" || customFont != null) && secondaryAlpha in .45f..1f && toolbarTransparency in 0f..1f
}

val ReaderSettings.normalBrightness: ReaderBrightness
    get() = brightnessPreferences?.takeIf { it.valid } ?: ReaderBrightness(
        mode = if (brightness < 0) "system" else "manual", level = brightness.takeIf { it in 0f..1f }?.coerceAtLeast(.01f) ?: .5f)

val ReaderSettings.activeBrightness: ReaderBrightness
    get() = if (nightReading) nightBrightness.takeIf { it.valid } ?: ReaderBrightness.night() else normalBrightness

val ReaderSettings.validNightReading: Boolean
    get() = nightPreset.valid && nightBrightness.valid && (brightnessPreferences == null || brightnessPreferences.valid) &&
        letterSpacing in 0f..0.15f

/** 渲染使用投影，始终保留原始普通配置，关闭夜间模式无需反向迁移。 */
fun ReaderSettings.readingAppearance(): ReaderSettings {
    if (!nightReading) return this
    val preset = nightPreset.takeIf { it.valid } ?: ReaderNightPreset()
    return copy(theme = preset.theme, monochrome = false, customColors = preset.customColors,
        fontSize = preset.fontSize ?: fontSize, lineHeight = preset.lineHeight ?: (lineHeight + .1f).coerceAtMost(4f),
        paragraphSpacing = preset.paragraphSpacing ?: paragraphSpacing, letterSpacing = preset.letterSpacing,
        weight = preset.weight, fontFamily = preset.fontFamily ?: fontFamily,
        customFont = if (preset.fontFamily == null) customFont else preset.customFont,
        secondaryAlpha = preset.secondaryAlpha, toolbarTransparency = preset.toolbarTransparency)
}

/** 偏好控件编辑实际显示值；夜间只把发生变化的外观项写入夜间预设。 */
fun ReaderSettings.withReadingAppearance(edited: ReaderSettings): ReaderSettings {
    if (!nightReading) return edited
    val shown = readingAppearance()
    val preset = nightPreset.copy(
        theme = if (edited.theme != shown.theme) edited.theme else nightPreset.theme,
        customColors = if (edited.customColors != shown.customColors) edited.customColors else nightPreset.customColors,
        fontSize = if (edited.fontSize != shown.fontSize) edited.fontSize else nightPreset.fontSize,
        lineHeight = if (edited.lineHeight != shown.lineHeight) edited.lineHeight else nightPreset.lineHeight,
        paragraphSpacing = if (edited.paragraphSpacing != shown.paragraphSpacing) edited.paragraphSpacing else nightPreset.paragraphSpacing,
        letterSpacing = edited.letterSpacing, weight = edited.weight,
        fontFamily = if (edited.fontFamily != shown.fontFamily || edited.customFont != shown.customFont) edited.fontFamily else nightPreset.fontFamily,
        customFont = if (edited.fontFamily != shown.fontFamily || edited.customFont != shown.customFont) edited.customFont else nightPreset.customFont,
        secondaryAlpha = edited.secondaryAlpha, toolbarTransparency = edited.toolbarTransparency)
    return edited.copy(theme = theme, monochrome = monochrome, customColors = customColors, fontSize = fontSize,
        lineHeight = lineHeight, paragraphSpacing = paragraphSpacing, letterSpacing = letterSpacing, weight = weight,
        fontFamily = fontFamily, customFont = customFont, secondaryAlpha = secondaryAlpha,
        toolbarTransparency = toolbarTransparency, nightPreset = preset)
}

fun ReaderSettings.withBrightness(preferences: ReaderBrightness): ReaderSettings =
    if (nightReading) copy(nightBrightness = preferences)
    else copy(brightnessPreferences = preferences, brightness = if (preferences.mode == "manual") preferences.level else -1f)

fun ReaderSettings.resetNightPreset() = copy(nightPreset = ReaderNightPreset(), nightBrightness = ReaderBrightness.night())
