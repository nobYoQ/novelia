@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.components.base

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.theme.ThemeBar
import cc.novelia.app.ui.theme.themeBarBackground

@Composable internal fun AppTopBar(title: @Composable () -> Unit, navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {}) {
    val bar = themeBarBackground(ThemeBar.Top, TopAppBarDefaults.topAppBarColors().containerColor)
    TopAppBar(title = title, modifier = bar.modifier.testTag("page-top-bar"), navigationIcon = navigationIcon, actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = bar.color, scrolledContainerColor = bar.color))
}

@Composable internal fun AppPageTabs(index: Int, scrollable: Boolean = false, tabs: @Composable () -> Unit) {
    val bar = themeBarBackground(ThemeBar.Top, TabRowDefaults.primaryContainerColor)
    val modifier = bar.modifier.testTag("page-tabs")
    if (scrollable) PrimaryScrollableTabRow(index, modifier = modifier, containerColor = bar.color,
        contentColor = TabRowDefaults.primaryContentColor, edgePadding = 12.dp, tabs = tabs)
    else PrimaryTabRow(index, modifier = modifier, containerColor = bar.color,
        contentColor = TabRowDefaults.primaryContentColor, tabs = tabs)
}
