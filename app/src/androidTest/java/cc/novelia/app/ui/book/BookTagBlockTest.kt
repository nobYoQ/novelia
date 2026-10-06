package cc.novelia.app.ui.book

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.ui.components.TagList
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.NoveliaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BookTagBlockTest {
    @get:Rule val compose = createComposeRule()

    @Test fun blocksOriginalTagAndCanUndoWithoutOpeningSearch() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        val before = app.store.state.value
        val tag = "異世界"
        try {
            app.store.update { it.copy(blockedTags = setOf("已有屏蔽")) }
            compose.setContent {
                NoveliaTheme("light") {
                    val nav = rememberNavController()
                    val scope = rememberCoroutineScope()
                    val c = remember { AppController(app, nav, scope, SnackbarHostState()) }
                    TagList(listOf(tag), c)
                }
            }
            compose.onNodeWithContentDescription("屏蔽标签 $tag").performClick()
            compose.runOnIdle { assertEquals(setOf("已有屏蔽", tag), app.store.state.value.blockedTags) }
            compose.onNodeWithContentDescription("取消屏蔽标签 $tag").assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(setOf("已有屏蔽"), app.store.state.value.blockedTags) }
        } finally { app.store.update { before }; runBlocking { app.store.flush() } }
    }
}
