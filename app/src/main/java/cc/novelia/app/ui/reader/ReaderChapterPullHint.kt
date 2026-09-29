package cc.novelia.app.ui.reader

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.LocalEInkMode
import cc.novelia.app.ui.theme.appReducedMotion

/** The existing chapter-end sentence is also the pull indicator; it never adds a second panel. */
@Composable internal fun ReaderChapterPullHint(
    progress: Float, active: Boolean, ready: Boolean, loading: Boolean, foreground: Color,
    modifier: Modifier = Modifier,
) {
    val eInk = LocalEInkMode.current
    val reducedMotion = appReducedMotion()
    val target = when { loading || ready -> 1f; eInk -> 0f; else -> progress }
    val returning by animateFloatAsState(target, tween(if(active || reducedMotion) 0 else AppMotion.Release), label = "chapter pull return")
    val fraction = if(active || reducedMotion) target else returning
    val color = foreground.copy(alpha = if(eInk) 1f else .55f + .45f * fraction)
    val text = when {
        loading -> "正在加载下一章…"
        ready -> "松手加载下一章"
        progress > 0f -> "继续上拉加载下一章"
        else -> "上拉加载下一章"
    }
    BoxWithConstraints(modifier.fillMaxWidth().heightIn(min = 40.dp).testTag("reader-chapter-pull-hint")) {
        val textWidth = maxWidth * .8f
        Row(Modifier.fillMaxWidth().align(Alignment.Center), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Canvas(Modifier.weight(1f).height(2.dp)) {
                drawLine(color, Offset(size.width * (1f - fraction), size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx())
            }
            Text(text, Modifier.widthIn(max = textWidth).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.labelMedium, color = color, textAlign = TextAlign.Center)
            Canvas(Modifier.weight(1f).height(2.dp)) {
                drawLine(color, Offset(0f, size.height / 2), Offset(size.width * fraction, size.height / 2), 1.dp.toPx())
            }
        }
    }
}
