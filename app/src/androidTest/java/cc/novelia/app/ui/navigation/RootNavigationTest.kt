package cc.novelia.app.ui.navigation

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
                composable("discover?query={query}") { Text("搜索") }
                composable("community") { Text("社区") }
            }
        }
        repeat(2) {
            compose.runOnIdle { nav.navigate("discover?query=tag") }
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
