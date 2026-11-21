package cc.novelia.app.ui.shelf

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** 与书目同层叠放。绘制区只占上沿，点击区不会参与列表和分区栏的测量。 */
@Composable internal fun BoxScope.ShelfControlsToggle(expanded: Boolean, hasActiveFilters: Boolean, onToggle: () -> Unit) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface.copy(alpha = .94f)
    val label = if(expanded) "收起书架工具栏" else "展开书架工具栏"
    Box(Modifier.align(Alignment.TopCenter).size(width = 64.dp, height = 44.dp)
        .testTag("shelf-controls-toggle")
        .clickable(role = Role.Button, onClick = onToggle)
        .semantics {
            contentDescription = label
            stateDescription = when {
                expanded -> "已展开"
                hasActiveFilters -> "已收起，有生效的筛选条件"
                else -> "已收起"
            }
        }) {
        Canvas(Modifier.align(Alignment.TopCenter).size(width = 32.dp, height = 8.dp)) {
            drawRoundRect(surface, cornerRadius = CornerRadius(4.dp.toPx()))
            val center = size.width / 2
            val spread = 9.dp.toPx()
            val upper = 2.dp.toPx()
            val lower = size.height - upper
            val chevron = Path().apply {
                moveTo(center - spread, if(expanded) lower else upper)
                lineTo(center, if(expanded) upper else lower)
                lineTo(center + spread, if(expanded) lower else upper)
            }
            drawPath(chevron, color, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            if(!expanded && hasActiveFilters) drawCircle(accent, radius = 1.5.dp.toPx(), center = Offset(center + 13.dp.toPx(), size.height / 2))
        }
    }
}
