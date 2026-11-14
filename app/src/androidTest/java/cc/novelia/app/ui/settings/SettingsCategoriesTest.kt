package cc.novelia.app.ui.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.saveTestScreenshot
import cc.novelia.app.ui.theme.NoveliaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class SettingsCategoriesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun automaticReadingSettingPreservesLegacyProtectionAndOffersExplicitBatchRestore() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        val original = app.store.state.value
        try {
            val book = cc.novelia.app.data.model.BookCard(cc.novelia.app.data.model.BookRef("syosetu", "settings-legacy-status"), "旧状态")
            app.store.update { it.copy(autoReadingStatus = true, books = listOf(cc.novelia.app.data.model.SavedBook(book, status = "读完"))) }
            val restoration = StateRestorationTester(compose)
            restoration.setContent { NoveliaTheme("light") {
                SettingsScreen(AppController(app, rememberNavController(), rememberCoroutineScope(), remember { SnackbarHostState() }), "LIBRARY")
            } }
            option("自动记录阅读状态").assertIsOn().performClick()
            compose.runOnIdle {
                assertFalse(app.store.state.value.autoReadingStatus)
                assertEquals("读完", app.store.state.value.books.single().status)
            }
            option("自动记录阅读状态").assertIsOff().performClick()
            compose.runOnIdle { assertNull(app.store.state.value.books.single().readingStatusControl) }
            runBlocking { app.store.flush() }
            restoration.emulateSavedInstanceStateRestore()
            option("自动记录阅读状态").assertIsOn()
            option("将已有书籍恢复自动管理").performClick()
            compose.runOnIdle {
                assertTrue(app.store.state.value.books.single().readingStatusControl!!.automatic)
                assertEquals("想读", app.store.state.value.books.single().status)
            }
        } finally {
            app.store.update { original }
            runBlocking { app.store.flush() }
        }
    }

    @Test fun categoriesHideUnrelatedOptionsAndRestoreSelectedPage() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        val restoration = StateRestorationTester(compose)
        restoration.setContent { NoveliaTheme("light") {
            SettingsScreen(AppController(app, rememberNavController(), rememberCoroutineScope(), remember { SnackbarHostState() }))
        } }
        compose.onNodeWithText("阅读与朗读").assertIsDisplayed()
        compose.onNodeWithText("默认阅读偏好").assertDoesNotExist()
        saveTestScreenshot("problem-settings-categories.png")
        option("网络与同步").performClick()
        option("书源线路").assertIsDisplayed()
        option("启用 ECH").assertIsDisplayed()
        saveTestScreenshot("network-sync-ech-setting.png")
        option("网络诊断与日志").assertIsDisplayed()
        compose.onNodeWithText("仅在 Wi-Fi 下载").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        option("启用 ECH").assertIsDisplayed()
        option("书源线路").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        option("书架与下载").performClick()
        option("移出书架时删除本地副本").assertIsDisplayed()
        compose.onNodeWithText("书源线路").assertDoesNotExist()
    }

    @Test fun startupUpdatePreferenceCanBeDisabledAndRestored() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        val original = app.store.state.value.autoCheckAppUpdates
        try {
            app.store.update { it.copy(autoCheckAppUpdates = true) }
            val restoration = StateRestorationTester(compose)
            restoration.setContent { NoveliaTheme("light") {
                SettingsScreen(AppController(app, rememberNavController(), rememberCoroutineScope(), remember { SnackbarHostState() }), "NETWORK")
            } }
            option("启动时检查应用更新").assertIsOn().performClick()
            compose.onNodeWithText("启动时检查应用更新").assertIsOff()
            compose.runOnIdle { assertFalse(app.store.state.value.autoCheckAppUpdates) }
            runBlocking { app.store.flush() }
            restoration.emulateSavedInstanceStateRestore()
            compose.runOnIdle { assertFalse(app.store.state.value.autoCheckAppUpdates) }
            option("启动时检查应用更新").assertIsOff().performClick()
            compose.onNodeWithText("启动时检查应用更新").assertIsOn()
            compose.runOnIdle { assertTrue(app.store.state.value.autoCheckAppUpdates) }
        } finally {
            app.store.update { it.copy(autoCheckAppUpdates = original) }
            runBlocking { app.store.flush() }
        }
    }
    @Test fun contentFiltersAreIndependentAndKeywordsHaveTheirOwnCategory() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        val original = app.store.state.value
        try {
            app.store.update { it.copy(hideUnavailableComments = false, hideLocalFavorites = false, hideCloudFavorites = false) }
            val restoration = StateRestorationTester(compose)
            restoration.setContent { NoveliaTheme("light") {
                SettingsScreen(AppController(app, rememberNavController(), rememberCoroutineScope(), remember { SnackbarHostState() }), "CONTENT")
            } }
            option("不显示已删除或已隐藏的评论").assertIsOff().performClick()
            saveTestScreenshot("content-comment-visibility-setting.png")
            option("屏蔽本地已收藏书籍").assertIsOff().performClick()
            option("屏蔽云端已收藏书籍").assertIsOff()
            saveTestScreenshot("content-favorite-visibility-settings.png")
            compose.runOnIdle {
                assertTrue(app.store.state.value.hideUnavailableComments)
                assertTrue(app.store.state.value.hideLocalFavorites)
                assertFalse(app.store.state.value.hideCloudFavorites)
            }
            compose.onNodeWithText("标签库与分类").assertDoesNotExist()
            runBlocking { app.store.flush() }
            restoration.emulateSavedInstanceStateRestore()
            option("不显示已删除或已隐藏的评论").assertIsOn()
            option("屏蔽本地已收藏书籍").assertIsOn().performClick()
            option("屏蔽云端已收藏书籍").assertIsOff().performClick()
            compose.runOnIdle {
                assertFalse(app.store.state.value.hideLocalFavorites)
                assertTrue(app.store.state.value.hideCloudFavorites)
            }
            compose.onNodeWithContentDescription("返回").performClick()
            option("标签与词库").performClick()
            option("标签库与分类").assertIsDisplayed()
            compose.onNodeWithText("隐藏小说评论").assertDoesNotExist()
        } finally {
            app.store.update { it.copy(hideUnavailableComments = original.hideUnavailableComments,
                hideLocalFavorites = original.hideLocalFavorites, hideCloudFavorites = original.hideCloudFavorites) }
            runBlocking { app.store.flush() }
        }
    }
    @Test fun shelfTapToReadCanBeEnabledAndDisabledAfterRestoration() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        val original = app.store.state.value.shelfTapToRead
        try {
            app.store.update { it.copy(shelfTapToRead = false) }
            val restoration = StateRestorationTester(compose)
            restoration.setContent { NoveliaTheme("light") {
                SettingsScreen(AppController(app, rememberNavController(), rememberCoroutineScope(), remember { SnackbarHostState() }), "LIBRARY")
            } }
            option("点击书架列表直接续读").assertIsOff()
            saveTestScreenshot("shelf-tap-to-read-setting.png")
            compose.onNodeWithText("点击书架列表直接续读").performClick()
            compose.runOnIdle { assertTrue(app.store.state.value.shelfTapToRead) }
            runBlocking { app.store.flush() }
            restoration.emulateSavedInstanceStateRestore()
            option("点击书架列表直接续读").assertIsOn().performClick()
            compose.runOnIdle { assertFalse(app.store.state.value.shelfTapToRead) }
        } finally {
            app.store.update { it.copy(shelfTapToRead = original) }
            runBlocking { app.store.flush() }
        }
    }

    private fun option(title: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(title))
        return compose.onNodeWithText(title)
    }
}
