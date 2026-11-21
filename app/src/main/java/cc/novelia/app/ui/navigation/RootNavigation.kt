package cc.novelia.app.ui.navigation

import android.net.Uri
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import cc.novelia.app.data.model.BookRef

internal const val SHOW_SHELF_LIST = "showShelfList"
internal const val SELECT_SHELF_BOOK = "selectShelfBook"
internal const val NEW_LOCAL_SERIES = "new"
internal const val LOCAL_SERIES_EDITOR_ROUTE = "local-series/edit/{id}?folder={folderId}&volumes={volumes}&comic={comic}"
internal const val DISCOVER_ROUTE = "discover?query={query}&category={category}"
internal const val COMMUNITY_ROUTE = "community?category={category}&query={query}"
internal const val SHELF_READER_DETAIL_BOOK = "shelfReaderDetailBook"
internal fun contentsChapterKey(ref: BookRef) = "contentsChapter:${ref.key}"

/** 优先回到已有详情；直接进入阅读器时替换当前页，避免返回键再次进入末章。 */
internal fun NavHostController.returnToBookContents(ref: BookRef, chapter: String) {
    val reader = currentBackStackEntry?.takeIf { it.destination.route?.startsWith("reader/") == true } ?: return
    val source = previousBackStackEntry
    val fromDetails = when (source?.destination?.route) {
        "book/{provider}/{id}" -> source.arguments?.getString("provider") == ref.provider && source.arguments?.getString("id") == ref.id
        "shelf" -> source.savedStateHandle.get<String>(SHELF_READER_DETAIL_BOOK) == ref.key
        else -> false
    }
    val target = contentsChapterKey(ref)
    if (source != null && fromDetails) {
        source.savedStateHandle[target] = chapter
        popBackStack()
        return
    }
    val route = "book/${ref.provider}/${Uri.encode(ref.id)}"
    navigate(route) {
        popUpTo(reader.destination.id) { inclusive = true }
    }
    currentBackStackEntry?.savedStateHandle?.set(target, chapter)
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
