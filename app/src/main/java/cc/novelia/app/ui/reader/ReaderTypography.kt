package cc.novelia.app.ui.reader

import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.data.model.ReaderTypographyDp
import cc.novelia.app.data.model.resolveDpTypography
import cc.novelia.app.data.model.withDpTypography
import kotlin.math.ceil

internal fun ReaderSettings.readerTypography(density: Density): ReaderTypographyDp =
    resolveDpTypography { with(density) { it.sp.toDp().value } }

internal fun ReaderSettings.inDp(density: Density): ReaderSettings =
    withDpTypography { with(density) { it.sp.toDp().value } }

internal fun ReaderTypographyDp.partFontSize(secondary: Boolean): Float = (fontSize - if(secondary) 1f else 0f).coerceAtLeast(1f)

internal fun formatReaderDp(value: Float): String = "%.2f".format(java.util.Locale.ROOT, value).trimEnd('0').trimEnd('.')

// 两行对应位置的距离 = 字号高度 + 额外行距；三个渲染入口共用同一像素计算。
internal fun readerLineHeightPx(fontSizePx: Float, lineSpacingPx: Float): Int =
    ceil((fontSizePx + lineSpacingPx).toDouble()).toInt().coerceAtLeast(1)

// 首尾行也保留同一行高，段距因此只表示段落之外额外增加的留白。
internal val ReaderLineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
