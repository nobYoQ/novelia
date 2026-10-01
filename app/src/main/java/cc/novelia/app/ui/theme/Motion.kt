package cc.novelia.app.ui.theme

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** 普通页面共用动画时长，阅读器和墨水屏策略仍决定是否播放动画。 */
internal object AppMotion {
    const val Quick = 160
    const val Standard = 220
    const val Panel = 280
    const val Press = 110
    const val Release = 180
    const val Exit = 120
    const val Page = 150
    const val Sticker = 900
    const val StickerRest = 1600
    const val StickerPress = 75
    const val StickerReaction = 360
    const val StickerHearts = 950
    const val StickerIdle = 4400
}

/** 也适用于未继承 Activity 提供器的预览和独立对话框。 */
@Composable internal fun appReducedMotion(): Boolean =
    LocalReducedMotion.current || LocalEInkMode.current || !ValueAnimator.areAnimatorsEnabled()

/** 为变化后的内容播放入场效果，不保留离开的页面，也不替换已有记忆状态。 */
@Composable
fun MotionContent(
    targetKey: Any?,
    modifier: Modifier = Modifier,
    animateInitial: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val reducedMotion = appReducedMotion()
    val progress = remember { Animatable(if(reducedMotion || !animateInitial) 1f else 0f) }
    var previousKey by remember { mutableStateOf(targetKey) }
    var started by remember { mutableStateOf(false) }
    val distance = with(LocalDensity.current) { 8.dp.toPx() }
    LaunchedEffect(targetKey, reducedMotion) {
        val shouldAnimate = !reducedMotion && (if(started) previousKey != targetKey else animateInitial)
        previousKey = targetKey
        started = true
        if(shouldAnimate) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(AppMotion.Standard, easing = FastOutSlowInEasing))
        } else {
            // 仅改变动画偏好，不应重播已经可见的内容。
            progress.snapTo(1f)
        }
    }
    // 在绘制阶段读取动画值，动画帧无需触发页面重组。
    Box(modifier.graphicsLayer {
        val fraction = if(reducedMotion) 1f else progress.value
        alpha = fraction
        translationY = (1f - fraction) * distance
    }, content = content)
}

/** 复用控件自身的交互源，让涟漪与按压反馈同步。 */
@Composable
fun Modifier.pressFeedback(interactionSource: MutableInteractionSource): Modifier {
    val reducedMotion = appReducedMotion()
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if(pressed && !reducedMotion) .985f else 1f,
        animationSpec = tween(if(reducedMotion) 0 else if(pressed) AppMotion.Press else AppMotion.Release, easing = FastOutSlowInEasing),
        label = "press feedback",
    )
    return graphicsLayer {
        val currentScale = if(reducedMotion) 1f else scale.value
        scaleX = currentScale
        scaleY = currentScale
    }
}

@Composable
fun Modifier.motionClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    // 点击语义和命中边界保持在视觉变换之外。
    return clickable(
        interactionSource = interactionSource,
        indication = LocalIndication.current,
        enabled = enabled,
        onClick = onClick,
    ).pressFeedback(interactionSource)
}
