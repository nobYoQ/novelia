package cc.novelia.app.ui.navigation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.appReducedMotion

/**
 * 导航栏属于根页面自身，与页面一起进退场。不能按当前路由在 NavHost 外增删导航栏：
 * 那会立刻改变仍在退场的页面尺寸，使书架重新排版，甚至跨过双栏布局的断点。
 * 系统底部边距由 NavHost 外的固定容器留出并消费，这里只增加应用导航栏占用的空间。
 */
@Composable
internal fun RootDestinationLayout(
    route: String,
    onNavigate: (String) -> Unit,
    hideBottomNavigation: Boolean = false,
    onNavigationBarHeightChanged: (Dp) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val tabs = listOf(
        Triple("shelf", "书架", Icons.Outlined.CollectionsBookmark),
        Triple("discover", "发现", Icons.Outlined.Explore),
        Triple("community", "社区", Icons.Outlined.Forum),
        Triple("profile", "我的", Icons.Outlined.PersonOutline),
    )
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val compactRail = maxHeight < 480.dp
        val density = LocalDensity.current
        if(wide || hideBottomNavigation) SideEffect { onNavigationBarHeightChanged(0.dp) }
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if(!wide && !hideBottomNavigation) NavigationBar(
                    modifier = Modifier.onSizeChanged { size ->
                        onNavigationBarHeightChanged(with(density) { size.height.toDp() })
                    },
                ) {
                    tabs.forEach { (target, label, icon) ->
                        val selected = route.startsWith(target)
                        NavigationBarItem(selected, { if(!selected) onNavigate(target) },
                            { NavigationIcon(icon, label, selected) }, label = { Text(label) })
                    }
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                if(wide) NavigationRail {
                    Spacer(Modifier.height(if(compactRail) 12.dp else 40.dp))
                    tabs.forEach { (target, label, icon) ->
                        val selected = route.startsWith(target)
                        NavigationRailItem(selected, { if(!selected) onNavigate(target) },
                            { NavigationIcon(icon, label, selected) },
                            label = if(compactRail) null else { { Text(label) } })
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) { content() }
            }
        }
    }
}

@Composable private fun NavigationIcon(icon: ImageVector, label: String, selected: Boolean) {
    val reducedMotion = appReducedMotion()
    val emphasis = animateFloatAsState(
        targetValue = if(selected) 1f else 0f,
        animationSpec = tween(if(reducedMotion) 0 else AppMotion.Release, easing = FastOutSlowInEasing),
        label = "navigation selection",
    )
    Icon(icon, label, Modifier.graphicsLayer {
        val scale = if(reducedMotion) 1f else 1f + .08f * emphasis.value
        scaleX = scale
        scaleY = scale
    })
}
