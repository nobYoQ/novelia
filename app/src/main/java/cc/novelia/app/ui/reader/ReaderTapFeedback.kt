package cc.novelia.app.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.theme.LocalEInkMode
import cc.novelia.app.ui.theme.appReducedMotion
import kotlinx.coroutines.delay

/** 绘制层反馈不参与测量、语义和命中测试，也不延迟实际翻页。 */
@Composable internal fun Modifier.readerTapFeedback(
    navigation: ReaderTapNavigation, foreground: Color, eInk: Boolean = LocalEInkMode.current
): Modifier {
    val reducedMotion = appReducedMotion() || eInk
    val feedback = navigation.feedback
    val opacity = remember { Animatable(0f) }
    LaunchedEffect(feedback, reducedMotion) {
        if(feedback != null) {
            opacity.snapTo(1f)
            if(reducedMotion) delay(180) else opacity.animateTo(0f, tween(280))
            navigation.clearFeedback(feedback)
        }
        opacity.snapTo(0f)
    }
    return drawWithContent {
        drawContent()
        if(feedback != null) {
            val strength = if(reducedMotion) 1f else opacity.value
            val regionWidth = size.width / 3f
            val left = regionWidth * (feedback.direction + 1)
            val region = Size(regionWidth, size.height)
            if(eInk) {
                // 电子纸只画出和清除一次细边框，不让整片正文连续变色。
                val stroke = 2.dp.toPx()
                drawRect(foreground, Offset(left + stroke / 2, stroke / 2),
                    Size((regionWidth - stroke).coerceAtLeast(0f), (size.height - stroke).coerceAtLeast(0f)), style = Stroke(stroke))
            } else {
                drawRect(foreground.copy(alpha = .07f * strength), Offset(left, 0f), region)
                if(feedback.direction != 0) {
                    val edge = foreground.copy(alpha = .10f * strength)
                    val colors = if(feedback.direction < 0) listOf(edge, Color.Transparent) else listOf(Color.Transparent, edge)
                    drawRect(Brush.horizontalGradient(colors, left, left + regionWidth), Offset(left, 0f), region)
                }
            }
        }
    }
}
