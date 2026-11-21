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
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
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

/** 与书目同层叠放。绘制区只占上沿，点击区不会参与列表和分区栏的测量。 */
@Composable internal fun BoxScope.ShelfControlsToggle(expanded: Boolean, hasActiveFilters: Boolean, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val handleColor = lerp(colors.surfaceContainerHigh, colors.primary, .06f)
    val reducedMotion = appReducedMotion()
    val direction = if(expanded) 1f else -1f
    val animatedDirection by animateFloatAsState(direction,
        tween(if(reducedMotion) 0 else AppMotion.Panel, easing = FastOutSlowInEasing), label = "shelf toolbar chevron")
    val label = if(expanded) "收起书架工具栏" else "展开书架工具栏"
    val viewConfiguration = LocalViewConfiguration.current
    val handleConfiguration = remember(viewConfiguration) {
        // 叠在书目上方的小箭头需要使用自己的命中范围，避免系统把它扩到第一排书籍上。
        object : ViewConfiguration by viewConfiguration {
            override val minimumTouchTargetSize = DpSize.Zero
        }
    }
    CompositionLocalProvider(LocalViewConfiguration provides handleConfiguration) {
        Box(Modifier.align(Alignment.TopCenter).size(width = 30.dp, height = 20.dp)
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
            Canvas(Modifier.align(Alignment.TopCenter).size(width = 24.dp, height = 12.dp)) {
                val middle = center
                val progress = if(reducedMotion) direction else animatedDirection
                val bend = 1.9.dp.toPx() * progress
                val halfSpan = 5.5.dp.toPx()
                drawRoundRect(handleColor, cornerRadius = CornerRadius(middle.y))
                val chevron = Path().apply {
                    moveTo(middle.x - halfSpan, middle.y + bend)
                    lineTo(middle.x, middle.y - bend)
                    lineTo(middle.x + halfSpan, middle.y + bend)
                }
                drawPath(chevron, colors.primary, style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                if(hasActiveFilters && progress < 1f) {
                    drawCircle(colors.primary, radius = 1.2.dp.toPx() * ((1f - progress) / 2f),
                        center = Offset(middle.x + 9.dp.toPx(), middle.y))
                }
            }
        }
    }
}
