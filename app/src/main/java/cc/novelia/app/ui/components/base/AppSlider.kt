@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.components.base

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import cc.novelia.app.ui.theme.LocalSquareCorners

/** 手势、键盘和无障碍仍交给 Material Slider，仅统一滑块和轨道的形状。 */
@Composable fun AppSlider(
    value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null, colors: SliderColors = SliderDefaults.colors(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }, steps: Int = 0,
    thumb: @Composable (SliderState) -> Unit = {
        if(LocalSquareCorners.current) Box(Modifier.size(4.dp, 44.dp).background(if(enabled) colors.thumbColor else colors.disabledThumbColor))
        else SliderDefaults.Thumb(interactionSource, colors = colors, enabled = enabled)
    },
    track: @Composable (SliderState) -> Unit = { AppSliderTrack(it, enabled = enabled, colors = colors) },
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
) = Slider(value, onValueChange, modifier, enabled, onValueChangeFinished, colors, interactionSource,
    steps, thumb, track, valueRange)

@Composable fun AppSliderTrack(
    state: SliderState, modifier: Modifier = Modifier, enabled: Boolean = true, colors: SliderColors = SliderDefaults.colors(),
) {
    if(!LocalSquareCorners.current) {
        SliderDefaults.Track(state, modifier = modifier, colors = colors, enabled = enabled)
        return
    }
    val active = if(enabled) colors.activeTrackColor else colors.disabledActiveTrackColor
    val inactive = if(enabled) colors.inactiveTrackColor else colors.disabledInactiveTrackColor
    Canvas(modifier.fillMaxWidth().height(16.dp)) {
        val fraction = ((state.value - state.valueRange.start) /
            (state.valueRange.endInclusive - state.valueRange.start)).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val position = size.width * fraction
        val rtl = layoutDirection == LayoutDirection.Rtl
        val gap = 8.dp.toPx()
        fun segment(start: Float, end: Float, color: androidx.compose.ui.graphics.Color) {
            if(end > start) drawRect(color, Offset(if(rtl) size.width - end else start, 0f), Size(end - start, size.height))
        }
        segment(0f, (position - gap).coerceAtLeast(0f), active)
        segment((position + gap).coerceAtMost(size.width), size.width, inactive)
        if(state.steps > 0) repeat(state.steps + 2) { index ->
            val at = index.toFloat() / (state.steps + 1)
            if(kotlin.math.abs(size.width * at - position) > gap) {
                val tick = 2.dp.toPx()
                val color = if(enabled) {
                    if(at <= fraction) colors.activeTickColor else colors.inactiveTickColor
                } else if(at <= fraction) colors.disabledActiveTickColor else colors.disabledInactiveTickColor
                val x = size.width * if(rtl) 1f - at else at
                drawRect(color, Offset((x - tick / 2).coerceIn(0f, (size.width - tick).coerceAtLeast(0f)),
                    (size.height - tick) / 2), Size(tick, tick))
            }
        }
    }
}
