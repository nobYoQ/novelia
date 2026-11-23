package cc.novelia.app.ui.navigation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.appReducedMotion
import cc.novelia.app.data.model.SquareCornerTarget
import cc.novelia.app.ui.theme.useSquareCorners
import cc.novelia.app.ui.theme.ThemeBar
import cc.novelia.app.ui.theme.isMonochromeTheme
import cc.novelia.app.ui.theme.monochromeDivider
import cc.novelia.app.ui.theme.pageContainerColor
import cc.novelia.app.ui.theme.themeBarBackground

/** 根页面内的选择操作栏接管手机底部导航，不影响其他路由或平板侧栏。 */
internal val LocalRootSelectionMode = staticCompositionLocalOf<(Boolean) -> Unit> { {} }

private val LocalRootNavigationPadding = compositionLocalOf<PaddingValues> { PaddingValues(0.dp) }

/** 导航栏在转场容器之外绘制。先测量栏位，再为仍在转场的根页面保留相同的空间。 */
@Composable
internal fun RootNavigationLayout(
    route: String?,
    onNavigate: (String) -> Unit,
    hideBottomNavigation: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable (barHeight: Dp, railWidth: Dp) -> Unit,
) {
    val tab = route?.substringBefore('?')
    val isRoot = rootTabs.any { it.first == tab }
    SubcomposeLayout(modifier.fillMaxSize()) { constraints ->
        val wide = constraints.maxWidth >= 600.dp.roundToPx()
        val compactRail = constraints.maxHeight < 480.dp.roundToPx()
        val navigation = subcompose("navigation") { RootNavigation(tab, onNavigate, wide, compactRail) }
            .single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val barHeight = if(wide) 0.dp else navigation.height.toDp()
        val railWidth = if(wide) navigation.width.toDp() else 0.dp
        val showNavigation = isRoot && (wide || !hideBottomNavigation)
        val pages = subcompose("pages") {
            CompositionLocalProvider(LocalRootNavigationPadding provides PaddingValues(start = railWidth, bottom = barHeight)) {
                Box(Modifier.fillMaxSize()) { content(if(showNavigation) barHeight else 0.dp, if(showNavigation) railWidth else 0.dp) }
            }
        }.single().measure(constraints)
        layout(pages.width, pages.height) {
            pages.placeRelative(0, 0)
            if(showNavigation) navigation.placeRelative(0, if(wide) 0 else pages.height - navigation.height)
        }
    }
}

@Composable
internal fun RootDestinationLayout(hideBottomNavigation: Boolean = false, content: @Composable () -> Unit) {
    val padding = LocalRootNavigationPadding.current
    val bottom = PaddingValues(bottom = if(hideBottomNavigation) 0.dp else padding.calculateBottomPadding())
    val rail = PaddingValues(start = padding.calculateStartPadding(LocalLayoutDirection.current))
    Box(Modifier.fillMaxSize().padding(rail).padding(bottom).consumeWindowInsets(bottom)
        .clipToBounds().background(pageContainerColor())) { content() }
}

private val rootTabs = listOf(
    Triple("shelf", "书架", Icons.Outlined.CollectionsBookmark),
    Triple("discover", "发现", Icons.Outlined.Explore),
    Triple("community", "社区", Icons.Outlined.Forum),
    Triple("profile", "我的", Icons.Outlined.PersonOutline),
)

@Composable private fun RootNavigation(route: String?, onNavigate: (String) -> Unit, wide: Boolean, compactRail: Boolean) {
    val colors = MaterialTheme.colorScheme
    val monochrome = isMonochromeTheme()
    if(wide) {
        val bar = themeBarBackground(ThemeBar.Bottom, NavigationRailDefaults.ContainerColor)
        val itemColors = if(monochrome) NavigationRailItemDefaults.colors(selectedIconColor = colors.onPrimary,
            indicatorColor = colors.primary) else NavigationRailItemDefaults.colors()
        NavigationRail(containerColor = bar.color, contentColor = colors.onSurface,
            modifier = bar.modifier.monochromeDivider(vertical = true).testTag("root-navigation")) {
            Spacer(Modifier.height(if(compactRail) 12.dp else 40.dp))
            rootTabs.forEach { (target, label, icon) ->
                val selected = route == target
                if(useSquareCorners(SquareCornerTarget.Navigation)) SquareNavigationItem(selected, { if(!selected) onNavigate(target) },
                    icon, label, Modifier.width(80.dp), showLabel = !compactRail)
                else NavigationRailItem(selected, { if(!selected) onNavigate(target) },
                    { NavigationIcon(icon, label, selected) }, colors = itemColors,
                    label = if(compactRail) null else { { NavigationLabel(label, monochrome && selected) } })
            }
        }
    } else {
        val bar = themeBarBackground(ThemeBar.Bottom, NavigationBarDefaults.containerColor)
        val itemColors = if(monochrome) NavigationBarItemDefaults.colors(selectedIconColor = colors.onPrimary,
            indicatorColor = colors.primary) else NavigationBarItemDefaults.colors()
        NavigationBar(containerColor = bar.color, contentColor = colors.onSurface,
            modifier = bar.modifier.monochromeDivider(atTop = true).testTag("root-navigation")) {
            rootTabs.forEach { (target, label, icon) ->
                val selected = route == target
                if(useSquareCorners(SquareCornerTarget.Navigation)) SquareNavigationItem(selected, { if(!selected) onNavigate(target) },
                    icon, label, Modifier.weight(1f))
                else NavigationBarItem(selected, { if(!selected) onNavigate(target) },
                    { NavigationIcon(icon, label, selected) }, colors = itemColors,
                    label = { NavigationLabel(label, monochrome && selected) })
            }
        }
    }
}

/** NavigationBarItem 的胶囊指示器不开放 shape 参数；直角模式保留标签和选中语义。 */
@Composable private fun SquareNavigationItem(
    selected: Boolean, onClick: () -> Unit, icon: ImageVector, label: String,
    modifier: Modifier = Modifier, showLabel: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val monochrome = isMonochromeTheme()
    val indicator = if(monochrome) colors.primary else colors.secondaryContainer
    val iconColor = when {
        !selected -> colors.onSurfaceVariant
        monochrome -> colors.onPrimary
        else -> colors.onSecondaryContainer
    }
    Column(modifier.heightIn(min = if(showLabel) 80.dp else 56.dp)
        .selectable(selected, role = Role.Tab, onClick = onClick).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(64.dp, 32.dp).background(if(selected) indicator else Color.Transparent),
            contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalContentColor provides iconColor) {
                NavigationIcon(icon, label, selected)
            }
        }
        if(showLabel) Text(label, color = if(selected) colors.onSurface else colors.onSurfaceVariant,
            fontWeight = if(monochrome && selected) FontWeight.Bold else null,
            style = MaterialTheme.typography.labelMedium)
    }
}

@Composable private fun NavigationLabel(label: String, emphasized: Boolean) {
    Text(label, fontWeight = if(emphasized) FontWeight.Bold else null)
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
