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

/** Shared timing for ordinary screens. Reader/e-ink policies still decide whether to animate. */
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

/** Also works for previews and isolated dialogs that do not inherit the activity's provider. */
@Composable internal fun appReducedMotion(): Boolean =
    LocalReducedMotion.current || LocalEInkMode.current || !ValueAnimator.areAnimatorsEnabled()

/** Reveals changed content without retaining an outgoing page or replacing its remembered state. */
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
            // Changing the preference alone must never replay already visible content.
            progress.snapTo(1f)
        }
    }
    // Read animated values during drawing: animation frames do not recompose the page.
    Box(modifier.graphicsLayer {
        val fraction = if(reducedMotion) 1f else progress.value
        alpha = fraction
        translationY = (1f - fraction) * distance
    }, content = content)
}

/** Uses the control's own interaction source, keeping ripple and press feedback in sync. */
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
    // Keep click semantics and hit bounds outside the visual transform.
    return clickable(
        interactionSource = interactionSource,
        indication = LocalIndication.current,
        enabled = enabled,
        onClick = onClick,
    ).pressFeedback(interactionSource)
}
