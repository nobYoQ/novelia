package cc.novelia.app.ui.book

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.theme.AppInteractionMode
import cc.novelia.app.ui.theme.NoveliaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BookDetailSwipeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun swipesAndTabClicksKeepInputAcrossResizeAndRecreation() {
        var selected = 0
        var width by mutableStateOf(390.dp)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            AppInteractionMode(eInk = false, reducedMotion = true) {
                NoveliaTheme("light") {
                    var tab by rememberSaveable { mutableIntStateOf(0) }
                    SideEffect { selected = tab }
                    AdaptiveBookDetail(tab, { tab = it }, listOf("简介", "目录", "讨论"), rememberSaveableStateHolder(),
                        Modifier.requiredWidth(width).height(480.dp)) { page ->
                        var input by rememberSaveable { mutableStateOf("") }
                        OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth().testTag("page-$page"))
                    }
                }
            }
        }
        compose.onNodeWithTag("page-0").performTextInput("简介位置")
        compose.onNodeWithTag("book-detail-pager").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("page-1").assertIsDisplayed().performTextInput("目录搜索")
        compose.runOnIdle { assertEquals(1, selected) }
        compose.onNodeWithTag("book-detail-pager").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("page-2").assertIsDisplayed()
        compose.onNodeWithTag("book-detail-pager").performTouchInput { swipeRight() }
        compose.onNodeWithTag("page-1").assertTextContains("目录搜索")
        compose.onNodeWithText("简介").performClick()
        compose.onNodeWithTag("page-0").assertTextContains("简介位置")
        compose.runOnIdle { width = 1000.dp }
        compose.onNodeWithTag("book-detail-dual-pane").assertExists()
        compose.runOnIdle { width = 390.dp }
        compose.onNodeWithTag("page-0").assertIsDisplayed().assertTextContains("简介位置")
        compose.onNodeWithText("目录").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("page-1").assertIsDisplayed().assertTextContains("目录搜索")
    }
}
