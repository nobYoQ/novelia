package cc.novelia.app.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.ui.theme.NoveliaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ForumFavoriteLoginTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var controller: AppController

    private fun scene(): StateRestorationTester {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        assertNull("专用测试环境应为论坛游客", app.forumSession.profile.value)
        return StateRestorationTester(compose).also { restoration -> restoration.setContent {
            NoveliaTheme("light") {
                val nav = rememberNavController()
                val scope = rememberCoroutineScope()
                val snackbar = remember { SnackbarHostState() }
                controller = remember(app, nav, scope, snackbar) { AppController(app, nav, scope, snackbar) }
                NavHost(nav, startDestination = "article") {
                    composable("article") { entry ->
                        val pending by entry.savedStateHandle.getStateFlow<String?>(FORUM_FAVORITE_REQUEST, null).collectAsState()
                        Column {
                            Button(onClick = { loginForForumFavorite(controller, 91001) }) { Text("收藏文章") }
                            pending?.let { Text("待收藏帖子 ${appJson.decodeFromString<ForumFavoriteRequest>(it).postId}") }
                        }
                    }
                    composable("forum-login") {
                        Column {
                            Button(onClick = { finishLoginNavigation(controller, forum = true) }) { Text("模拟论坛登录完成") }
                            Button(onClick = { controller.back() }) { Text("取消论坛登录") }
                        }
                    }
                }
            }
        } }
    }

    @Test fun forumFavoriteSurvivesRecreationAndReturnsToTheArticleOnlyOnce() {
        val restoration = scene()
        compose.onNodeWithText("收藏文章").performClick()
        val before = controller
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("模拟论坛登录完成").assertIsDisplayed().performClick()
        compose.onNodeWithText("待收藏帖子 91001").assertIsDisplayed()
        compose.runOnIdle {
            assertNotSame(before, controller)
            assertNull(controller.afterLogin)
            controller.nav.currentBackStackEntry!!.savedStateHandle.set<String?>(FORUM_FAVORITE_REQUEST, null)
            finishLoginNavigation(controller, forum = true)
        }
        compose.onNodeWithText("待收藏帖子 91001").assertDoesNotExist()
        var ordinaryLogins = 0
        compose.runOnIdle { controller.requireForumLogin { ordinaryLogins++ } }
        compose.onNodeWithText("模拟论坛登录完成").performClick()
        compose.runOnIdle { assertEquals(1, ordinaryLogins) }
        compose.onNodeWithText("待收藏帖子 91001").assertDoesNotExist()
    }

    @Test fun cancelingForumLoginDropsTheFavoriteAndClearsAnEarlierCallback() {
        scene()
        var earlierCallback = false
        compose.runOnIdle { controller.afterLogin = { earlierCallback = true } }
        compose.onNodeWithText("收藏文章").performClick()
        compose.onNodeWithText("取消论坛登录").performClick()
        compose.onNodeWithText("待收藏帖子 91001").assertDoesNotExist()
        compose.runOnIdle { controller.requireForumLogin {} }
        compose.onNodeWithText("模拟论坛登录完成").performClick()
        compose.onNodeWithText("待收藏帖子 91001").assertDoesNotExist()
        compose.runOnIdle { assertFalse(earlierCallback) }
    }
}
