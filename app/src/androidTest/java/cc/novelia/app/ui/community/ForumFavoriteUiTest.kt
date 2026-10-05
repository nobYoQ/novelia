package cc.novelia.app.ui.community

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.theme.LocalReducedMotion
import cc.novelia.app.ui.theme.NoveliaTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class ForumFavoriteUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun failedSaveCanRetryAndCancelWaitsForConfirmation() {
        val favorite = ForumFavoriteState(false)
        var response = CompletableDeferred<Unit>()
        val writes = mutableListOf<Boolean>()
        var failure: Exception? = null
        compose.setContent { NoveliaTheme("light") {
            val scope = rememberCoroutineScope()
            Surface { ForumFavoriteButton(favorite) {
                scope.launch {
                    try { favorite.setSaved(!favorite.saved) { writes += it; response.await() } }
                    catch(error: Exception) { failure = error }
                }
            } }
        } }
        compose.onNodeWithText("收藏文章").performClick()
        compose.onNodeWithText("正在同步…").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf(true), writes); assertFalse(favorite.saved); response.completeExceptionally(IOException("请求失败")) }
        compose.onNodeWithText("收藏文章").assertIsEnabled()
        compose.runOnIdle { assertNotNull(failure); response = CompletableDeferred() }
        compose.onNodeWithText("收藏文章").performClick()
        compose.runOnIdle { response.complete(Unit) }
        compose.onNodeWithText("取消收藏").assertIsEnabled()
        compose.runOnIdle { response = CompletableDeferred() }
        compose.onNodeWithText("取消收藏").performClick()
        compose.onNodeWithText("正在同步…").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(favorite.saved); response.complete(Unit) }
        compose.onNodeWithText("收藏文章").assertIsEnabled()
        compose.runOnIdle { assertEquals(listOf(true, true, false), writes); assertFalse(favorite.saved) }
    }

    @Test fun cloudFavoriteRemainsUsableWithLargeTextAndReducedMotion() {
        val favorite = ForumFavoriteState(true)
        var clicked = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f), LocalReducedMotion provides true) {
                NoveliaTheme("dark") {
                    Surface(Modifier.width(320.dp)) {
                        FlowRow(Modifier.padding(20.dp)) { ForumFavoriteButton(favorite) { clicked = true } }
                    }
                }
            }
        }
        compose.onNodeWithText("取消收藏").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(clicked) }
        compose.onNodeWithText("本地收藏").assertDoesNotExist()
        compose.onNodeWithText("收藏到论坛账号").assertDoesNotExist()
    }
}
