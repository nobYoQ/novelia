package cc.novelia.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.SquareCornerTarget

/** 总开关关闭时提供空集合，组件只读取自己的分类。 */
internal val LocalSquareCornerTargets = staticCompositionLocalOf { emptySet<SquareCornerTarget>() }

private val ZeroCorner = RoundedCornerShape(0.dp)
internal val SquareShapes = Shapes(ZeroCorner, ZeroCorner, ZeroCorner, ZeroCorner, ZeroCorner)
internal val DefaultShapes = Shapes()

@Composable @ReadOnlyComposable
internal fun useSquareCorners(target: SquareCornerTarget): Boolean = target in LocalSquareCornerTargets.current

@Composable @ReadOnlyComposable
fun appShape(shape: Shape, target: SquareCornerTarget = SquareCornerTarget.Cards): Shape =
    if(useSquareCorners(target)) RectangleShape else shape

@Composable @ReadOnlyComposable
fun appRoundedCornerShape(size: Dp, target: SquareCornerTarget = SquareCornerTarget.Cards): Shape =
    appShape(RoundedCornerShape(size), target)

@Composable @ReadOnlyComposable
fun appRoundedCornerShape(topStart: Dp = 0.dp, topEnd: Dp = 0.dp, bottomEnd: Dp = 0.dp, bottomStart: Dp = 0.dp,
    target: SquareCornerTarget = SquareCornerTarget.Cards): Shape =
    appShape(RoundedCornerShape(topStart, topEnd, bottomEnd, bottomStart), target)

@Composable @ReadOnlyComposable
internal fun appControlShape(shape: Shape = DefaultShapes.extraSmall): Shape = appShape(shape, SquareCornerTarget.Controls)

@Composable @ReadOnlyComposable
internal fun appPanelShape(shape: Shape = DefaultShapes.extraLarge): Shape = appShape(shape, SquareCornerTarget.Panels)

private val SheetShape = DefaultShapes.extraLarge.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp))

@Composable @ReadOnlyComposable
internal fun appBottomSheetShape(): Shape = appPanelShape(SheetShape)

@Composable @ReadOnlyComposable
internal fun appBookCoverShape(): Shape = appRoundedCornerShape(12.dp, SquareCornerTarget.Covers)
