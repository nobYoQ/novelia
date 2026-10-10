@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.reader

import cc.novelia.app.ui.components.AppSlider
import cc.novelia.app.ui.components.AppSliderTrack

import cc.novelia.app.ui.components.AppTextButton
import cc.novelia.app.ui.components.AppOutlinedButton

import androidx.compose.animation.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import cc.novelia.app.ui.theme.LocalSquareCorners
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.markdown.format
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.LocalEInkMode
import cc.novelia.app.ui.theme.appReducedMotion
import kotlin.math.abs
import kotlin.math.max

@Composable internal fun ReaderSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, modifier: Modifier = Modifier,
    enabled: Boolean = true, livePreviewStep: Float? = null, defaultValue: Float? = null, onChange: (Float) -> Unit) {
    val reference = defaultValue?.takeIf { it.isFinite() }?.coerceIn(range)
    val name = label.substringBefore(' ')
    val resetDescription = reference?.let { "恢复$name 默认值 ${formatPreferenceValue(it)}" }.orEmpty()
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        if(LocalEInkMode.current) {
            val step = when { range.endInclusive - range.start > 100f -> 20f; range.endInclusive - range.start > 10f -> 1f; else -> .05f }
            Row(modifier.fillMaxWidth().semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(range), range)
                if(!enabled) disabled()
                setProgress { next -> if(enabled && next.isFinite()) { onChange(next.coerceIn(range)); true } else false }
            }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                AppOutlinedButton(onClick = { onChange((value - step).coerceIn(range)) }, enabled = enabled && value > range.start, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Icon(Icons.Outlined.Remove, "减小 $label") }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(formatPreferenceValue(value), style = MaterialTheme.typography.labelLarge)
                    if(reference != null) AppTextButton(onClick = { onChange(reference) }, enabled = enabled,
                        modifier = Modifier.testTag("reader-default-$name").semantics { contentDescription = resetDescription }) {
                        Text("默认 ${formatPreferenceValue(reference)}", style = MaterialTheme.typography.labelSmall)
                    }
                }
                AppOutlinedButton(onClick = { onChange((value + step).coerceIn(range)) }, enabled = enabled && value < range.endInclusive, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Icon(Icons.Outlined.Add, "增大 $label") }
            }
        } else {
            ReaderProgressSlider(name, value, range, modifier, enabled, livePreviewStep, reference, resetDescription, onChange)
        }
    }
}

@Composable private fun ReaderProgressSlider(name: String, value: Float, range: ClosedFloatingPointRange<Float>, modifier: Modifier,
    enabled: Boolean, livePreviewStep: Float?, reference: Float?, resetDescription: String, onChange: (Float) -> Unit) {
    val squareCorners = LocalSquareCorners.current
    val reducedMotion = appReducedMotion()
    var draft by remember { mutableFloatStateOf(value) }
    var dragging by remember { mutableStateOf(false) }
    var lastPreviewed by remember { mutableFloatStateOf(value) }
    var resetting by remember { mutableStateOf(false) }
    var referenceTap by remember { mutableStateOf(false) }
    var gestureChanged by remember { mutableStateOf(false) }
    LaunchedEffect(value, dragging) {
        if(!dragging) {
            if(draft != value) { resetting = false; draft = value }
            lastPreviewed = value
        }
    }
    LaunchedEffect(reducedMotion) { if(reducedMotion) resetting = false }
    val animatedValue by animateFloatAsState(draft, if(resetting && !reducedMotion) tween(AppMotion.Standard) else snap(),
        label = "reader-default-reset", finishedListener = { resetting = false })
    // 只为复位播放位置动画；拖动、键盘调节和外部更新立即跟随输入。
    val displayed = if(resetting && !reducedMotion) animatedValue else draft
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val scheme = MaterialTheme.colorScheme
    val targetColor = if(reference != null && draft > reference) lerp(scheme.primary, Color.Black, .22f) else scheme.primary
    val colorAnimation = remember { Animatable(targetColor) }
    LaunchedEffect(targetColor, reducedMotion) {
        // 取消正在运行的渐变并同步内部值，重新开启动效时不会续播旧颜色。
        if(reducedMotion) colorAnimation.snapTo(targetColor)
        else if(colorAnimation.value != targetColor) colorAnimation.animateTo(targetColor, tween(AppMotion.Standard))
    }
    val coveredColor = if(reducedMotion) targetColor else colorAnimation.value
    val overflowColor = lerp(scheme.surface, scheme.primary, .46f)
    var sliderStart by remember { mutableFloatStateOf(0f) }
    var sliderWidth by remember { mutableFloatStateOf(0f) }
    var trackStart by remember { mutableFloatStateOf(0f) }
    var trackWidth by remember { mutableFloatStateOf(0f) }
    val span = range.endInclusive - range.start
    val referenceFraction = reference?.let { ((it - range.start) / span).coerceIn(0f, 1f) }
    val fraction = ((displayed - range.start) / span).coerceIn(0f, 1f)
    val resetBounds = if(reference != null && referenceFraction != null && trackWidth > 0 && sliderWidth > 0) {
        fun x(f: Float) = trackStart - sliderStart + trackWidth * if(rtl) 1f - f else f
        val start = x(if(displayed < reference) fraction else 0f)
        val end = x(referenceFraction)
        val width = max(abs(end - start), with(density) { 48.dp.toPx() }).coerceAtMost(sliderWidth)
        val left = ((start + end - width) / 2).coerceIn(0f, sliderWidth - width)
        Rect(left, 0f, left + width, with(density) { 48.dp.toPx() })
    } else Rect.Zero
    val latestBounds by rememberUpdatedState(resetBounds)
    val reset: () -> Unit = {
        if(enabled && reference != null) {
            resetting = !reducedMotion
            dragging = false
            gestureChanged = false
            draft = reference
            lastPreviewed = reference
            onChange(reference)
        }
    }
    val latestReset by rememberUpdatedState(reset)
    Box(Modifier.fillMaxWidth().height(48.dp).onGloballyPositioned {
        sliderStart = it.positionInRoot().x
        sliderWidth = it.size.width.toFloat()
    }.pointerInput(reference, enabled) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            referenceTap = enabled && reference != null && !down.isConsumed && latestBounds.contains(down.position)
            gestureChanged = false
            try {
                while(true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                    if(pointer.isConsumed || event.changes.size != 1 || (pointer.position - down.position).getDistance() > viewConfiguration.touchSlop) referenceTap = false
                    if(!pointer.pressed) {
                        if(referenceTap && pointer.previousPressed) {
                            // 在 Slider 处理抬手之前提交精确默认值，避免先跳到点击坐标。
                            pointer.consume()
                            latestReset()
                        }
                        awaitPointerEvent(PointerEventPass.Final)
                        break
                    }
                }
            } finally { referenceTap = false }
        }
    }) {
        AppSlider(displayed, { next ->
            if(!referenceTap) {
                resetting = false
                draft = next
                dragging = true
                gestureChanged = true
                if(livePreviewStep != null && abs(next - lastPreviewed) >= livePreviewStep) {
                    lastPreviewed = next
                    onChange(next)
                }
            }
        }, modifier = modifier.fillMaxWidth().height(48.dp), valueRange = range, enabled = enabled,
            onValueChangeFinished = {
                if(gestureChanged) { gestureChanged = false; dragging = false; onChange(draft) }
            }, track = { slider ->
                Box(Modifier.testTag("reader-track-$name").onGloballyPositioned {
                    trackStart = it.positionInRoot().x
                    trackWidth = it.size.width.toFloat()
                }) {
                    AppSliderTrack(slider, enabled = enabled)
                    if(referenceFraction != null) AppSliderTrack(slider, enabled = enabled,
                        colors = SliderDefaults.colors(activeTrackColor = coveredColor, inactiveTrackColor = overflowColor,
                            disabledActiveTrackColor = coveredColor.copy(alpha = .38f), disabledInactiveTrackColor = overflowColor.copy(alpha = .38f)),
                        modifier = Modifier.matchParentSize().drawWithContent {
                            val end = size.width * referenceFraction
                            if(end > 0f) clipPath(Path().apply {
                                addRoundRect(RoundRect(if(rtl) size.width - end else 0f, 0f, if(rtl) size.width else end, size.height, CornerRadius(if(squareCorners) 0f else size.height / 2)))
                            }) { this@drawWithContent.drawContent() }
                        })
                }
            })
        if(reference != null && resetBounds.width > 0f) {
            // 语义节点不拦截拖动；实际触摸由外层区分点击、横向拖动和列表滚动。
            Box(Modifier.absoluteOffset(x = with(density) { resetBounds.left.toDp() })
                .width(with(density) { resetBounds.width.toDp() }).height(48.dp).testTag("reader-default-$name")
                .semantics {
                    contentDescription = resetDescription
                    role = Role.Button
                    if(enabled) onClick("恢复默认值") { reset(); true } else disabled()
                })
        }
    }
}

private fun formatPreferenceValue(value: Float): String = "%.2f".format(value).trimEnd('0').trimEnd('.')
