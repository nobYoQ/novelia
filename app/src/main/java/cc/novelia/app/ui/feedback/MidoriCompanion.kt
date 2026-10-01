package cc.novelia.app.ui.feedback

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import cc.novelia.app.R
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.appReducedMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 使用原始本地 WebP 素材，保留透明边缘和宽高比。 */
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
// 颜色取自原始贴纸中的爱心，避免被理解为错误或状态提示。
private val StickerHeartPink = Color(0xFFE15C86)

@Composable
internal fun stickerMotionEnabled(): Boolean {
    val durationScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]
    return !appReducedMotion() &&
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
    val squash = remember { Animatable(0f) }
    val lift = remember { Animatable(0f) }
    val tilt = remember { Animatable(0f) }
    var bursts by remember { mutableStateOf(emptyList<Int>()) }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val sticker = if(reacting && active) HappyReactions[reactionIndex] else MidoriSticker.Neutral

    // 同时只保留一个可替换反馈，快速点击重新开始，不累积粒子或任务。
    LaunchedEffect(taps, active) {
        if(!active || taps == 0) {
            reacting = false
            return@LaunchedEffect
        }
        delay(1800)
        reacting = false
    }
    // 中断时从当前形变继续，连点不会先跳回单位缩放或突然归零摇摆。
    LaunchedEffect(taps, animate, reacting, pressed) {
        if(!animate) {
            squash.snapTo(0f); lift.snapTo(0f); tilt.snapTo(0f)
            bursts = emptyList()
            return@LaunchedEffect
        }
        when {
            pressed -> {
                launch { squash.animateTo(1f, spring(dampingRatio = .8f, stiffness = 650f)) }
                launch { lift.animateTo(0f, spring(stiffness = 650f)) }
                tilt.animateTo(-3f, spring(dampingRatio = .8f, stiffness = 500f))
            }
            reacting -> {
                launch {
                    squash.animateTo(.9f, tween(65))
                    squash.animateTo(0f, spring(dampingRatio = .36f, stiffness = 450f))
                }
                launch {
                    lift.animateTo(-10f, tween(130, easing = FastOutSlowInEasing))
                    lift.animateTo(0f, spring(dampingRatio = .42f, stiffness = 360f))
                }
                tilt.animateTo(if(taps % 2 == 0) -7f else 7f, tween(130))
                tilt.animateTo(0f, spring(dampingRatio = .4f, stiffness = 300f))
            }
            else -> {
                launch { squash.animateTo(0f, spring(dampingRatio = .7f)) }
                launch { lift.animateTo(0f, spring(dampingRatio = .7f)) }
                tilt.animateTo(0f, spring(dampingRatio = .8f))
                while(true) {
                    tilt.animateTo(-3f, tween(800, easing = FastOutSlowInEasing))
                    tilt.animateTo(3f, tween(1200, easing = FastOutSlowInEasing))
                    tilt.animateTo(0f, tween(800, easing = FastOutSlowInEasing))
                    delay(1600)
                }
            }
        }
    }

    Box(
        modifier.size(128.dp)
            .semantics {
                contentDescription = "小绿，阅读搭子"
                stateDescription = sticker.description
            }
            .clickable(
                interactionSource = interactionSource,
                indication = if(reducedMotion) null else ripple(bounded = false, radius = 56.dp),
                enabled = active, role = Role.Button, onClickLabel = "打个招呼，切换表情",
            ) {
                reactionIndex = (reactionIndex + 1) % HappyReactions.size
                reacting = true
                taps++
                if(animate) bursts = (bursts + taps).takeLast(2)
            },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(sticker, modifier = Modifier.size(112.dp).testTag("midori-artwork").graphicsLayer {
            // 在绘制阶段读取动画帧，卡片和账号数据无需逐帧重组。
            transformOrigin = TransformOrigin(.5f, .85f)
            rotationZ = if(animate) tilt.value else 0f
            translationY = if(animate) lift.value.dp.toPx() else 0f
            scaleX = if(animate) 1f + squash.value * .09f else 1f
            scaleY = if(animate) 1f - squash.value * .1f else 1f
        }, animationSpec = tween(if(reducedMotion || !active) 0 else 120), label = "midori expression") {
            MidoriIllustration(it, Modifier.fillMaxSize())
        }
        if(active && reacting) {
            if(reducedMotion) ReactionHearts(animated = false)
            else bursts.forEach { burst -> key(burst) {
                ReactionHearts(animated = true) { bursts = bursts - burst }
            } }
        }
    }
}

/** 最多保留两组独立爱心，下一次点击不会把正在飘走的爱心拉回原位。 */
@Composable private fun BoxScope.ReactionHearts(animated: Boolean, onFinished: () -> Unit = {}) {
    val hearts = remember { Animatable(0f) }
    val finished by rememberUpdatedState(onFinished)
    LaunchedEffect(animated) {
        if(animated) { hearts.animateTo(1f, tween(AppMotion.StickerHearts)); finished() }
    }
    repeat(3) { index ->
        Icon(Icons.Filled.Favorite, null, tint = StickerHeartPink,
            modifier = Modifier.size(if(index == 1) 16.dp else 12.dp).graphicsLayer {
                val progress = if(!animated) .65f else hearts.value
                val fraction = ((progress - index * .09f) / .82f).coerceIn(0f, 1f)
                val direction = index - 1f
                translationX = direction * (18f + fraction * 20f).dp.toPx()
                translationY = (-4f - fraction * (if(index == 1) 44f else 32f)).dp.toPx()
                rotationZ = direction * 14f
                alpha = if(!animated) 1f else ((1f - fraction) * 3f).coerceIn(0f, 1f) * (fraction * 8f).coerceIn(0f, 1f)
                scaleX = if(!animated) 1f else .6f + fraction * .5f
                scaleY = scaleX
            },
        )
    }
}
