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

@Composable internal fun AppInteractionMode(eInk: Boolean, reducedMotion: Boolean, content: @Composable () -> Unit) {
    val durationScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]
    val static = eInk || reducedMotion || !android.animation.ValueAnimator.areAnimatorsEnabled() ||
        (durationScale?.scaleFactor ?: 1f) == 0f
    CompositionLocalProvider(
        LocalEInkMode provides eInk,
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
