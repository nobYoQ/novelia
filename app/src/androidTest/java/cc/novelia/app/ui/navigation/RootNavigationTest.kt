package cc.novelia.app.ui.navigation

import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
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
}
