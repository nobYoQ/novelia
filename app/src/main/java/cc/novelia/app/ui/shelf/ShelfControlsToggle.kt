package cc.novelia.app.ui.shelf

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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
import cc.novelia.app.ui.theme.LocalEInkMode
import cc.novelia.app.ui.theme.appReducedMotion

@Composable internal fun ShelfControlsPanel(visible: Boolean, collapsed: Boolean, modifier: Modifier, tag: String,
    onCollapsed: () -> Unit, footer: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    if(!visible) return
    val closing by rememberUpdatedState(collapsed)
    val finishCollapse by rememberUpdatedState(onCollapsed)
    Column(modifier.fillMaxWidth()) {
        FilterPanelVisibility(expanded = !collapsed) {
            // 等整个工具栏退出后再收起搜索框，避免两个高度动画同时改变书目起点。
            DisposableEffect(Unit) { onDispose { if(closing) finishCollapse() } }
            Column(Modifier.fillMaxWidth().testTag(tag), content = content)
        }
        footer()
    }
}

/** 工具栏下沿的整行是点击区，始终位于书目上方。 */
@Composable internal fun ShelfControlsToggle(
    expanded: Boolean, hasActiveFilters: Boolean, enabled: Boolean, onToggle: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val background = colors.surfaceContainerLow.copy(alpha = if(LocalEInkMode.current) 1f else .6f)
    val reducedMotion = appReducedMotion()
    val direction = if(expanded) 1f else -1f
    val animatedDirection by animateFloatAsState(direction,
        tween(if(reducedMotion) 0 else AppMotion.Panel, easing = FastOutSlowInEasing), label = "shelf toolbar chevron")
    val label = if(expanded) "收起书架工具栏" else "展开书架工具栏"
    val viewConfiguration = LocalViewConfiguration.current
    val handleConfiguration = remember(viewConfiguration) {
        // 禁止 Compose 把实际命中区扩展到相邻的工具行、标签或第一本书。
        object : ViewConfiguration by viewConfiguration {
            override val minimumTouchTargetSize = DpSize.Zero
        }
    }
    val interaction = if(enabled) Modifier
        .testTag("shelf-controls-toggle")
        .clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onToggle)
        .semantics {
            contentDescription = label
            stateDescription = when {
                expanded -> "已展开"
                hasActiveFilters -> "已收起，有生效的筛选条件"
                else -> "已收起"
            }
        } else Modifier
    CompositionLocalProvider(LocalViewConfiguration provides handleConfiguration) {
        Canvas(Modifier.fillMaxWidth().height(28.dp).background(background).then(interaction)) {
            val progress = if(reducedMotion) direction else animatedDirection
            val bend = 2.5.dp.toPx() * progress
            val halfSpan = 6.dp.toPx()
            val chevron = Path().apply {
                moveTo(center.x - halfSpan, center.y + bend)
                lineTo(center.x, center.y - bend)
                lineTo(center.x + halfSpan, center.y + bend)
            }
            drawPath(chevron, colors.primary, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            if(hasActiveFilters && progress < 1f) {
                drawCircle(colors.primary, radius = 1.5.dp.toPx() * ((1f - progress) / 2f),
                    center = Offset(center.x + 11.dp.toPx(), center.y))
            }
        }
    }
}
