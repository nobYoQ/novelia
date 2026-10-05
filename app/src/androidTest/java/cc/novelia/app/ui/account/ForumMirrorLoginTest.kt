package cc.novelia.app.ui.account

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.network.BookSource
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.NoveliaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class ForumMirrorLoginTest {
    @get:Rule val compose = createComposeRule()

    @Test fun anonymousForumOnMirrorUsesNativeLoginAfterRecreation() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        assumeTrue("镜像入口需要构建配置", app.bookSources.capture().hasAccessToken)
        val previous = app.bookSources.capture().source
        try {
            app.bookSources.select(BookSource.XKVI)
            assertNull("专用模拟器应为小说镜像游客", app.session.profile.value)
            assertNull("专用模拟器应为论坛镜像游客", app.forumSession.profile.value)
            val restoration = StateRestorationTester(compose)
            restoration.setContent {
                NoveliaTheme("light") {
                    val nav = rememberNavController()
                    val scope = rememberCoroutineScope()
                    val snackbar = remember { SnackbarHostState() }
                    val controller = remember(app, nav, scope, snackbar) { AppController(app, nav, scope, snackbar) }
                    NavHost(nav, startDestination = "forum-login") {
                        composable("forum-login") { LoginScreen(controller, forum = true) }
                    }
                }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("通过镜像登录论坛").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("用户名或邮箱").assertIsDisplayed()
            compose.onNodeWithText("密码").assertIsDisplayed()
            compose.onNodeWithText("完成登录").assertDoesNotExist()
            restoration.emulateSavedInstanceStateRestore()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("通过镜像登录论坛").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("没有账号，注册").performScrollTo().performClick()
            compose.onNodeWithText("通过镜像注册").assertIsDisplayed()
            compose.onNodeWithText("邮箱验证码").performScrollTo().assertIsDisplayed()
        } finally { app.bookSources.select(previous) }
    }
}
