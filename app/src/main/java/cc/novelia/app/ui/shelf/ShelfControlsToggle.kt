package cc.novelia.app.ui.shelf

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.components.base.FilterPanelVisibility
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.appReducedMotion
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable internal fun rememberShelfControlsScroll(contentKey: Any, enabled: Boolean): ShelfControlsScroll {
    val threshold = with(LocalDensity.current) { 12.dp.toPx() }
    return remember(contentKey, enabled, threshold) { ShelfControlsScroll(threshold, enabled) }
}

internal class ShelfControlsScroll(private val threshold: Float, private val enabled: Boolean) : NestedScrollConnection {
    var tucked by mutableStateOf(false)
        private set
    private var travel = 0f

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        // 只观察书目实际滚过的距离，惯性、回弹和横向切页不会反向触发按钮。
        if(enabled && source == NestedScrollSource.UserInput && consumed.y != 0f) {
            val forward = consumed.y < 0f
            travel = if(forward == tucked) 0f else travel + abs(consumed.y)
            if(travel >= threshold) {
                tucked = forward
                travel = 0f
            }
        }
        return Offset.Zero
    }

    fun onPageTurn(direction: Int, state: LazyListState) {
        if(enabled && (direction < 0 || state.canScrollForward)) {
            tucked = direction > 0
            travel = 0f
        }
    }
}

@Composable internal fun ShelfControlsPanel(visible: Boolean, collapsed: Boolean, modifier: Modifier, tag: String,
    onCollapsed: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    if(!visible) return
    val closing by rememberUpdatedState(collapsed)
    val finishCollapse by rememberUpdatedState(onCollapsed)
    Box(modifier.fillMaxWidth()) {
        FilterPanelVisibility(expanded = !collapsed) {
            // 等整个工具栏退出后再收起搜索框，避免两个高度动画同时改变书目起点。
            DisposableEffect(Unit) { onDispose { if(closing) finishCollapse() } }
            Column(Modifier.fillMaxWidth().testTag(tag), content = content)
        }
    }
}

/** 与书目同层叠放，向上缩回时只绘制并命中书目上沿露出的部分。 */
@Composable internal fun BoxScope.ShelfControlsToggle(
    expanded: Boolean, hasActiveFilters: Boolean, tucked: Boolean, followScroll: Boolean, onToggle: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val handleColor = lerp(colors.surfaceContainerHigh, colors.secondaryContainer, .7f)
    val reducedMotion = appReducedMotion()
    val revealTarget = if(tucked || !followScroll) 0f else 1f
    val reveal by if(followScroll && !reducedMotion) {
        animateFloatAsState(revealTarget, tween(AppMotion.Panel, easing = FastOutSlowInEasing), label = "shelf toolbar button")
    } else rememberUpdatedState(revealTarget)
    val direction = if(expanded) 1f else -1f
    val animatedDirection by animateFloatAsState(direction,
        tween(if(reducedMotion) 0 else AppMotion.Panel, easing = FastOutSlowInEasing), label = "shelf toolbar chevron")
    val label = if(expanded) "收起书架工具栏" else "展开书架工具栏"
    val viewConfiguration = LocalViewConfiguration.current
    val handleConfiguration = remember(viewConfiguration) {
        // 半圆缩回时，点击区不延伸到工具栏或已露出的书目上。
        object : ViewConfiguration by viewConfiguration {
            override val minimumTouchTargetSize = DpSize.Zero
        }
    }
    CompositionLocalProvider(LocalViewConfiguration provides handleConfiguration) {
        Canvas(Modifier.align(Alignment.TopCenter).width(44.dp)
            .layout { measurable, constraints ->
                val fraction = reveal
                val inset = (6.dp.toPx() * fraction).roundToInt().coerceAtMost(constraints.maxHeight)
                val height = (22.dp.toPx() * (1f + fraction)).roundToInt().coerceAtMost(constraints.maxHeight - inset)
                val button = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
                layout(button.width, height + inset) { button.placeRelative(0, inset) }
            }
            .clipToBounds()
            .testTag("shelf-controls-toggle")
            .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onToggle)
            .semantics {
                contentDescription = label
                stateDescription = when {
                    expanded -> "已展开"
                    hasActiveFilters -> "已收起，有生效的筛选条件"
                    else -> "已收起"
                }
            }) {
            val fraction = reveal
            val radius = size.width / 2f
            val circle = Offset(radius, size.height - radius)
            drawCircle(handleColor, radius - .5.dp.toPx(), circle)
            drawCircle(colors.outlineVariant, radius - .5.dp.toPx(), circle, style = Stroke(1.dp.toPx()))
            // 缩回后箭头仍居中显示在露出的半圆内。
            val middle = circle.copy(y = circle.y + 8.dp.toPx() * (1f - fraction))
            val progress = if(reducedMotion) direction else animatedDirection
            val bend = 2.5.dp.toPx() * progress
            val halfSpan = 7.dp.toPx()
            val chevron = Path().apply {
                moveTo(middle.x - halfSpan, middle.y + bend)
                lineTo(middle.x, middle.y - bend)
                lineTo(middle.x + halfSpan, middle.y + bend)
            }
            drawPath(chevron, colors.primary, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            if(hasActiveFilters && progress < 1f) {
                drawCircle(colors.primary, radius = 1.5.dp.toPx() * ((1f - progress) / 2f),
                    center = Offset(middle.x + 12.dp.toPx(), middle.y))
            }
        }
    }
}
