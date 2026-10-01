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
        // 起始页面仍在返回栈中；若恢复其已保存栈，
        // 可能立即把从详情进入的搜索页重新放到起始页面之上。
        restoreState = target != "shelf"
    }
}
