package cc.novelia.app.data.model

import kotlinx.serialization.Serializable
import kotlin.math.round

private fun dpValue(value: Float, range: ClosedFloatingPointRange<Float>): Float =
    (round(value * 10_000f) / 10_000f).coerceIn(range)

/** 正文排版统一使用 dp；行距为字号之外额外增加的距离。 */
@Serializable data class ReaderTypographyDp(
    val fontSize: Float = 19f,
    val lineSpacing: Float = 15.2f,
    val letterSpacing: Float = 0f,
) {
    val valid get() = fontSize in FONT_SIZE_RANGE && lineSpacing in LINE_SPACING_RANGE && letterSpacing in LETTER_SPACING_RANGE
    companion object {
        val FONT_SIZE_RANGE = 8f..128f
        val LINE_SPACING_RANGE = 0f..256f
        val LETTER_SPACING_RANGE = 0f..32f
    }
}

/** 夜间可分别继承普通字号和行距；继承时的额外留白也保存为固定 dp。 */
@Serializable data class ReaderNightTypographyDp(
    val fontSize: Float? = null,
    val lineSpacing: Float? = null,
    val letterSpacing: Float = .38f,
    val inheritedLineSpacingExtra: Float = 1.9f,
) {
    val valid get() = (fontSize == null || fontSize in ReaderTypographyDp.FONT_SIZE_RANGE) &&
        (lineSpacing == null || lineSpacing in ReaderTypographyDp.LINE_SPACING_RANGE) &&
        letterSpacing in ReaderTypographyDp.LETTER_SPACING_RANGE && inheritedLineSpacingExtra in -256f..256f

    fun resolve(ordinary: ReaderTypographyDp) = ReaderTypographyDp(fontSize ?: ordinary.fontSize,
        lineSpacing ?: (ordinary.lineSpacing + inheritedLineSpacingExtra).coerceIn(ReaderTypographyDp.LINE_SPACING_RANGE), letterSpacing)
}

/** 老字段继续保留给历史备份；只在首次使用时按当前设备的实际显示尺寸换算。 */
fun ReaderSettings.resolveDpTypography(spToDp: (Float) -> Float): ReaderTypographyDp =
    typographyDp?.takeIf { it.valid } ?: run {
        val size = dpValue(spToDp(fontSize), ReaderTypographyDp.FONT_SIZE_RANGE)
        ReaderTypographyDp(size, dpValue(size * (lineHeight - 1f), ReaderTypographyDp.LINE_SPACING_RANGE),
            dpValue(size * letterSpacing, ReaderTypographyDp.LETTER_SPACING_RANGE))
    }

fun ReaderSettings.withDpTypography(spToDp: (Float) -> Float): ReaderSettings {
    val ordinary = resolveDpTypography(spToDp)
    val preset = nightPreset.takeIf { it.valid } ?: ReaderNightPreset()
    val night = preset.typographyDp?.takeIf { it.valid } ?: run {
        val size = preset.fontSize?.let { dpValue(spToDp(it), ReaderTypographyDp.FONT_SIZE_RANGE) } ?: ordinary.fontSize
        val explicitSpacing = preset.lineHeight?.let { dpValue(size * (it - 1f), ReaderTypographyDp.LINE_SPACING_RANGE) }
        val inheritedExtra = if(typographyDp == null) {
            dpValue(size * (lineHeight + .1f - 1f), ReaderTypographyDp.LINE_SPACING_RANGE) - ordinary.lineSpacing
        } else size * .1f
        ReaderNightTypographyDp(preset.fontSize?.let { size }, explicitSpacing,
            dpValue(size * preset.letterSpacing, ReaderTypographyDp.LETTER_SPACING_RANGE), dpValue(inheritedExtra, -256f..256f))
    }
    return copy(typographyDp = ordinary, nightPreset = preset.copy(typographyDp = night))
}
