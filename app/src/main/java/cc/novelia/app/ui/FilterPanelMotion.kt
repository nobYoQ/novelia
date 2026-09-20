package cc.novelia.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

@Composable internal fun FilterPanelExpandIcon(expanded: Boolean, description: String? = null) {
    val reducedMotion = appReducedMotion()
    val rotation by animateFloatAsState(if(expanded) 180f else 0f,
        tween(if(reducedMotion) 0 else AppMotion.Panel, easing = FastOutSlowInEasing), label = "filter arrow")
    Icon(Icons.Outlined.ExpandMore, description, Modifier.graphicsLayer {
        rotationZ = if(reducedMotion) if(expanded) 180f else 0f else rotation
    })
}

@Composable internal fun FilterPanelVisibility(expanded: Boolean, content: @Composable () -> Unit) {
    // Switching reduced motion on also finishes an already-running transition immediately.
    if(appReducedMotion()) {
        if(expanded) content()
    } else AnimatedVisibility(expanded,
        enter = expandVertically(tween(AppMotion.Panel, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) + fadeIn(tween(AppMotion.Quick)),
        exit = shrinkVertically(tween(AppMotion.Panel, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top) + fadeOut(tween(AppMotion.Quick))) {
        content()
    }
}
