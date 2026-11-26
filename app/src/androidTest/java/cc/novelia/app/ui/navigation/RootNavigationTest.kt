package cc.novelia.app.ui.navigation

import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.markdown.MarkdownLinks
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RootNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun shelfClickFromTagSearchDoesNotRestoreSearchAboveShelf() {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "shelf") {
                composable("shelf") { Text("书架") }
                composable(DISCOVER_ROUTE) { Text("搜索") }
                composable(COMMUNITY_ROUTE) { Text("社区") }
            }
        }
        val author = "作者 A&B / 100% + 新作"
        for(category in listOf(null, "2")) {
            compose.runOnIdle {
                nav.navigate("discover?query=${Uri.encode(author)}" + if(category == null) "" else "&category=$category")
            }
            compose.runOnIdle {
                assertEquals(author, nav.currentBackStackEntry?.arguments?.getString("query"))
                assertEquals(category, nav.currentBackStackEntry?.arguments?.getString("category"))
            }
            compose.runOnIdle { nav.switchRootTab("shelf") }
            compose.runOnIdle {
                assertEquals("shelf", nav.currentDestination?.route)
                assertEquals(true, nav.currentBackStackEntry?.savedStateHandle?.get<Boolean>(SHOW_SHELF_LIST))
                nav.currentBackStackEntry?.savedStateHandle?.set(SHOW_SHELF_LIST, false)
            }
        }
        compose.runOnIdle { nav.switchRootTab("community") }
        compose.runOnIdle { nav.switchRootTab("shelf") }
        compose.runOnIdle { assertEquals("shelf", nav.currentDestination?.route) }
    }

    @Test fun lastChapterReturnsToExistingBookContentsAndReplacesADirectReaderEntry() {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "shelf") {
                composable("shelf") { Text("书架") }
                composable("book/{provider}/{id}") { Text("作品详情") }
                composable("reader/{provider}/{id}/{chapter}") { Text("阅读器") }
            }
        }
        val ref = BookRef("local", "书名 A&B")
        val bookRoute = "book/local/${Uri.encode(ref.id)}"
        for(fromDetails in listOf(true, false)) {
            var originalId: String? = null
            compose.runOnIdle {
                if(fromDetails) nav.navigate(bookRoute)
                originalId = nav.currentBackStackEntry?.id
                nav.navigate("reader/local/${Uri.encode(ref.id)}/last")
                nav.returnToBookContents(ref, "last")
            }
            compose.runOnIdle {
                assertEquals("book/{provider}/{id}", nav.currentDestination?.route)
                assertEquals(ref.id, nav.currentBackStackEntry?.arguments?.getString("id"))
                if (fromDetails) assertEquals(originalId, nav.currentBackStackEntry?.id)
                else assertEquals(originalId, nav.previousBackStackEntry?.id)
                assertEquals("last", nav.currentBackStackEntry?.savedStateHandle?.get<String>(contentsChapterKey(ref)))
                nav.popBackStack()
            }
            compose.runOnIdle { assertEquals("shelf", nav.currentDestination?.route) }
        }
    }

    @Test fun publishingLinksPreserveCategoryAndEncodedSearchAndCanReturnToTheDraft() {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "compose") {
                composable("compose") { Text("写一篇帖子") }
                composable(COMMUNITY_ROUTE) { Text("社区") }
            }
        }
        val query = "求书 A&B / 100% +"
        compose.runOnIdle {
            nav.currentBackStackEntry?.savedStateHandle?.set("draft", "未发布正文")
            nav.navigate(requireNotNull(MarkdownLinks.nativeRoute("https://forum.novelia.cc/c/novel?q=${Uri.encode(query)}")))
        }
        compose.runOnIdle {
            assertEquals("novel", nav.currentBackStackEntry?.arguments?.getString("category"))
            assertEquals(query, nav.currentBackStackEntry?.arguments?.getString("query"))
            nav.popBackStack()
        }
        compose.runOnIdle {
            assertEquals("compose", nav.currentDestination?.route)
            assertEquals("未发布正文", nav.currentBackStackEntry?.savedStateHandle?.get<String>("draft"))
        }
    }
}
