package cc.novelia.app.ui.navigation

import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController

internal const val SHOW_SHELF_LIST = "showShelfList"

internal fun NavHostController.switchRootTab(target: String) {
    if (target == "shelf") {
        getBackStackEntry("shelf").savedStateHandle[SHOW_SHELF_LIST] = true
    }
    navigate(target) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        // The start destination is still on the stack. Restoring its saved stack
        // can immediately put a detail-linked search back above it.
        restoreState = target != "shelf"
    }
}
