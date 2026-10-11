package cc.novelia.app.ui.components.base

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
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.appReducedMotion

@Composable internal fun FilterPanelExpandIcon(expanded: Boolean, description: String? = null) {
    val reducedMotion = appReducedMotion()
    val rotation by animateFloatAsState(if(expanded) 180f else 0f,
        tween(if(reducedMotion) 0 else AppMotion.Panel, easing = FastOutSlowInEasing), label = "filter arrow")
    Icon(Icons.Outlined.ExpandMore, description, Modifier.graphicsLayer {
        rotationZ = if(reducedMotion) if(expanded) 180f else 0f else rotation
    })
}

@Composable internal fun FilterPanelVisibility(expanded: Boolean, content: @Composable () -> Unit) {
    // 开启减少动画后，正在进行的过渡也立即结束。
    if(appReducedMotion()) {
        if(expanded) content()
    } else AnimatedVisibility(expanded,
        enter = expandVertically(tween(AppMotion.Panel, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) + fadeIn(tween(AppMotion.Quick)),
        exit = shrinkVertically(tween(AppMotion.Panel, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top) + fadeOut(tween(AppMotion.Quick))) {
        content()
    }
}
