package cc.novelia.app.ui.reader

import androidx.compose.ui.text.style.LineHeightStyle
import kotlin.math.ceil

// 先换算正文实际字号，再应用行高倍数，避免系统非线性缩放改变倍数的含义。
internal fun readerLineHeightPx(fontSizePx: Float, multiplier: Float): Int =
    ceil((fontSizePx * multiplier).toDouble()).toInt().coerceAtLeast(1)

// 首尾行也保留同一行高，段距因此只表示段落之外额外增加的留白。
internal val ReaderLineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
