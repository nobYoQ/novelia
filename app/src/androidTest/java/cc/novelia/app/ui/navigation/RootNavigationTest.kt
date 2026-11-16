package cc.novelia.app.ui.navigation

import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import cc.novelia.app.data.model.BookRef
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
                composable("community") { Text("社区") }
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
            compose.runOnIdle {
                if(fromDetails) nav.navigate(bookRoute)
                nav.navigate("reader/local/${Uri.encode(ref.id)}/last")
                nav.returnToBookContents(ref)
            }
            compose.runOnIdle {
                assertEquals("book/{provider}/{id}", nav.currentDestination?.route)
                assertEquals(ref.id, nav.currentBackStackEntry?.arguments?.getString("id"))
                assertEquals(true, nav.currentBackStackEntry?.savedStateHandle?.get<Boolean>(SHOW_BOOK_CONTENTS))
                nav.popBackStack()
            }
            compose.runOnIdle { assertEquals("shelf", nav.currentDestination?.route) }
        }
    }

}
