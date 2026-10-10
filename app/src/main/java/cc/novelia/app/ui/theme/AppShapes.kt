package cc.novelia.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 本机的直角彩蛋；通过组合局部值传播到阅读器、独立窗口和弹层。 */
val LocalSquareCorners = staticCompositionLocalOf { false }

private val ZeroCorner = RoundedCornerShape(0.dp)
internal val SquareShapes = Shapes(ZeroCorner, ZeroCorner, ZeroCorner, ZeroCorner, ZeroCorner)
internal val DefaultShapes = Shapes()

@Composable @ReadOnlyComposable
fun appShape(shape: Shape): Shape = if(LocalSquareCorners.current) RectangleShape else shape

@Composable @ReadOnlyComposable
fun appRoundedCornerShape(size: Dp): Shape = appShape(RoundedCornerShape(size))

@Composable @ReadOnlyComposable
fun appRoundedCornerShape(topStart: Dp = 0.dp, topEnd: Dp = 0.dp, bottomEnd: Dp = 0.dp, bottomStart: Dp = 0.dp): Shape =
    appShape(RoundedCornerShape(topStart, topEnd, bottomEnd, bottomStart))
