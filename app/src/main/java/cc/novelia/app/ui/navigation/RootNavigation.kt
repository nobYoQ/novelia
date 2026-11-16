package cc.novelia.app.ui.navigation

import android.net.Uri
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import cc.novelia.app.data.model.BookRef

internal const val SHOW_SHELF_LIST = "showShelfList"
internal const val DISCOVER_ROUTE = "discover?query={query}&category={category}"
internal const val SHOW_BOOK_CONTENTS = "showBookContents"

/** 优先回到已有详情；直接进入阅读器时替换当前页，避免返回键再次进入末章。 */
internal fun NavHostController.returnToBookContents(ref: BookRef) {
    val route = "book/${ref.provider}/${Uri.encode(ref.id)}"
    val existing = runCatching { getBackStackEntry(route) }.getOrNull()
    if(existing != null) {
        existing.savedStateHandle[SHOW_BOOK_CONTENTS] = true
        if(popBackStack(route, inclusive = false)) return
    }
    val readerDestination = currentDestination?.takeIf { it.route?.startsWith("reader/") == true }?.id
    navigate(route) {
        if(readerDestination != null) popUpTo(readerDestination) { inclusive = true }
        launchSingleTop = true
    }
    currentBackStackEntry?.savedStateHandle?.set(SHOW_BOOK_CONTENTS, true)
}

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
