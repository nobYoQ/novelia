@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.components.base

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.SquareCornerTarget
import cc.novelia.app.ui.theme.DefaultShapes
import cc.novelia.app.ui.theme.appPanelShape
import cc.novelia.app.ui.theme.useSquareCorners

@Composable fun AppLinearProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = ProgressIndicatorDefaults.linearColor,
    trackColor: Color = ProgressIndicatorDefaults.linearTrackColor,
    strokeCap: StrokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
    gapSize: Dp = ProgressIndicatorDefaults.LinearIndicatorTrackGapSize,
) = LinearProgressIndicator(modifier, color, trackColor,
    if(useSquareCorners(SquareCornerTarget.Navigation)) StrokeCap.Butt else strokeCap, gapSize)

@Composable fun AppLinearProgressIndicator(
    progress: () -> Float, modifier: Modifier = Modifier,
    color: Color = ProgressIndicatorDefaults.linearColor,
    trackColor: Color = ProgressIndicatorDefaults.linearTrackColor,
    strokeCap: StrokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
    gapSize: Dp = ProgressIndicatorDefaults.LinearIndicatorTrackGapSize,
    drawStopIndicator: (DrawScope.() -> Unit)? = null,
) {
    val cap = if(useSquareCorners(SquareCornerTarget.Navigation)) StrokeCap.Butt else strokeCap
    if(drawStopIndicator == null) LinearProgressIndicator(progress, modifier, color, trackColor, cap, gapSize)
    else LinearProgressIndicator(progress, modifier, color, trackColor, cap, gapSize, drawStopIndicator)
}

@Composable fun AppBadge(modifier: Modifier = Modifier) {
    if(useSquareCorners(SquareCornerTarget.Navigation)) Box(modifier.size(6.dp).background(BadgeDefaults.containerColor))
    else Badge(modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AppSheetDragHandle() = BottomSheetDefaults.DragHandle(shape = appPanelShape(DefaultShapes.extraLarge))
