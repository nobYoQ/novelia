package cc.novelia.app.ui

import android.animation.ValueAnimator
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import cc.novelia.app.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Original local WebP assets; preserve their transparent edges and aspect ratio. */
enum class MidoriSticker(@get:DrawableRes val drawable: Int, val description: String) {
    Neutral(R.drawable.midori_neutral, "等你打招呼"),
    Happy(R.drawable.midori_happy, "开心地笑了"),
    Wink(R.drawable.midori_wink, "向你眨眨眼"),
    Love(R.drawable.midori_love, "收到你的喜欢啦"),
    Welcome(R.drawable.midori_welcome, "欢迎新的故事"),
    Curious(R.drawable.midori_curious, "正在寻找故事"),
    Reading(R.drawable.midori_reading, "安静阅读"),
    Thinking(R.drawable.midori_thinking, "若有所思"),
    Celebrate(R.drawable.midori_celebrate, "开心庆祝"),
    Approve(R.drawable.midori_approve, "完成啦"),
    Sleep(R.drawable.midori_sleep, "安心休息"),
    Concerned(R.drawable.midori_concerned, "遇到一点小状况"),
    Wave(R.drawable.midori_wave, "向你招手"),
}

@Composable
fun MidoriIllustration(sticker: MidoriSticker, modifier: Modifier = Modifier) {
    Image(
        painterResource(sticker.drawable), contentDescription = null,
        modifier = modifier, contentScale = ContentScale.Fit,
    )
}

private val HappyReactions = listOf(MidoriSticker.Happy, MidoriSticker.Wink, MidoriSticker.Love)
// Matches the hearts in the original sticker artwork, rather than an error/status color.
private val StickerHeartPink = Color(0xFFE15C86)

@Composable
internal fun stickerMotionEnabled(): Boolean {
    val durationScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]
    return !LocalReducedMotion.current && ValueAnimator.areAnimatorsEnabled() &&
        (durationScale?.scaleFactor ?: 1f) > 0f
}

@Composable
fun MidoriCompanion(modifier: Modifier = Modifier, visible: Boolean = true) {
    var resumed by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        resumed = true
        onPauseOrDispose { resumed = false }
    }
    val active = visible && resumed
    val reducedMotion = !stickerMotionEnabled()
    val animate = active && !reducedMotion
    var taps by remember { mutableIntStateOf(0) }
    var reactionIndex by remember { mutableIntStateOf(-1) }
    var reacting by remember { mutableStateOf(false) }
    val pop = remember { Animatable(1f) }
    val hearts = remember { Animatable(1f) }
    val interactionSource = remember { MutableInteractionSource() }
    val sticker = if(reacting && active) HappyReactions[reactionIndex] else MidoriSticker.Neutral

    // One replaceable reaction: rapid taps restart it without accumulating particles or jobs.
    LaunchedEffect(taps, active) {
        if(!active || taps == 0) {
            reacting = false
            return@LaunchedEffect
        }
        delay(1800)
        reacting = false
    }
    LaunchedEffect(taps, active, reducedMotion, reacting) {
        pop.snapTo(1f)
        hearts.snapTo(1f)
        if(!animate || !reacting) return@LaunchedEffect
        launch {
            pop.animateTo(.88f, tween(75))
            pop.animateTo(1f, keyframes {
                durationMillis = 360
                .88f at 0 using FastOutSlowInEasing
                1.12f at 140 using FastOutSlowInEasing
                .97f at 260 using FastOutSlowInEasing
                1f at 360
            })
        }
        hearts.snapTo(0f)
        hearts.animateTo(1f, tween(950))
    }

    // A gentle sway followed by a rest, only while the card is visible and the app is resumed.
    val sway = if(animate && !reacting) {
        rememberInfiniteTransition(label = "midori idle").animateFloat(
            initialValue = 0f, targetValue = 0f,
            animationSpec = infiniteRepeatable(keyframes {
                durationMillis = 4400
                0f at 0 using FastOutSlowInEasing
                -4f at 800 using FastOutSlowInEasing
                4f at 2000 using FastOutSlowInEasing
                0f at 2800
                0f at 4400
            }), label = "midori sway",
        )
    } else null

    Box(
        modifier.size(128.dp)
            .semantics {
                contentDescription = "小绿，阅读搭子"
                stateDescription = sticker.description
            }
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = false, radius = 56.dp),
                enabled = active, role = Role.Button, onClickLabel = "打个招呼，切换表情",
            ) {
                reactionIndex = (reactionIndex + 1) % HappyReactions.size
                reacting = true
                taps++
            },
        contentAlignment = Alignment.Center,
    ) {
        MidoriIllustration(sticker, Modifier.size(112.dp).graphicsLayer {
            // Read frames in the draw phase; the card and account data do not recompose.
            transformOrigin = TransformOrigin(.5f, .85f)
            rotationZ = sway?.value ?: 0f
            scaleX = if(animate) pop.value else 1f
            scaleY = scaleX
        })
        if(reacting && active) {
            repeat(3) { index ->
                Icon(Icons.Filled.Favorite, null, tint = StickerHeartPink,
                    modifier = Modifier.size(if(index == 1) 16.dp else 12.dp).graphicsLayer {
                        val progress = if(reducedMotion) .65f else hearts.value
                        val fraction = ((progress - index * .09f) / .82f).coerceIn(0f, 1f)
                        val direction = index - 1f
                        translationX = direction * (18f + fraction * 20f).dp.toPx()
                        translationY = (-4f - fraction * (if(index == 1) 44f else 32f)).dp.toPx()
                        rotationZ = direction * 14f
                        alpha = if(reducedMotion) 1f else ((1f - fraction) * 3f).coerceIn(0f, 1f) * (fraction * 8f).coerceIn(0f, 1f)
                        scaleX = if(reducedMotion) 1f else .6f + fraction * .5f
                        scaleY = scaleX
                    },
                )
            }
        }
    }
}
