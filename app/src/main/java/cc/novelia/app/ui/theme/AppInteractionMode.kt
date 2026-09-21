@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode

/**
 * 把应用偏好与系统动画开关合并为子树的交互约束，并统一关闭波纹、指示动画和过度滚动。
 * LocalEInkMode 还会影响翻屏方式，LocalReducedMotion 只表达静态显示需求，二者不能混为一谈。
 * 页面可嵌套此入口，为单书电子纸偏好建立局部作用域。
 */
internal val LocalScrollPageButtons = staticCompositionLocalOf { true }

@Composable internal fun AppInteractionMode(eInk: Boolean, reducedMotion: Boolean,
    showScrollPageButtons: Boolean = LocalScrollPageButtons.current, content: @Composable () -> Unit) {
    val durationScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]
    val static = eInk || reducedMotion || !android.animation.ValueAnimator.areAnimatorsEnabled() ||
        (durationScale?.scaleFactor ?: 1f) == 0f
    CompositionLocalProvider(
        LocalEInkMode provides eInk,
        LocalScrollPageButtons provides showScrollPageButtons,
        LocalReducedMotion provides static,
        LocalRippleConfiguration provides if(static) null else LocalRippleConfiguration.current,
        LocalIndication provides if(static) StaticIndication else LocalIndication.current,
        LocalOverscrollFactory provides if(static) null else LocalOverscrollFactory.current,
        content = content
    )
}

private object StaticIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): Modifier.Node = object : Modifier.Node(), DrawModifierNode {
        override fun ContentDrawScope.draw() { drawContent() }
    }
    override fun equals(other: Any?) = other === this
    override fun hashCode() = javaClass.hashCode()
}
