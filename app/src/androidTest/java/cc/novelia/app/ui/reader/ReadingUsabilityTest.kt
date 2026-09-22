package cc.novelia.app.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.ui.account.AccountPermissionsCard
import cc.novelia.app.ui.community.CommentMoreMenu
import cc.novelia.app.ui.community.blockCommentUser
import cc.novelia.app.ui.components.ChoiceRow
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.NoveliaTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ReadingUsabilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun permissionsStayCollapsedUntilRequestedAndKeepAnAccessibleTarget() {
        compose.setContent { NoveliaTheme("light") { AccountPermissionsCard("阅读者", canPost = true, canEdit = false) } }
        compose.onNodeWithText("社区发布").assertDoesNotExist()
        compose.onNodeWithTag("account-permissions-toggle").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("社区发布").assertIsDisplayed()
        compose.onNodeWithText("书籍编辑").assertIsDisplayed()
        capture("account-permissions")
        compose.onNodeWithTag("account-permissions-toggle").performClick()
        compose.onNodeWithText("社区发布").assertDoesNotExist()
    }

    @Test fun preferencesGroupAdvancedSettingsAndKeepPagingSwitchesIndependent() {
        var settings by mutableStateOf(ReaderSettings())
        compose.setContent { NoveliaTheme("light") { ReaderPreferences(settings) { settings = it } } }
        compose.onNodeWithText("常用").assertIsSelected().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("显示语言").assertExists()
        capture("reader-preferences-common")
        compose.onNodeWithText("语言与译文").performScrollTo().assertHasNoClickAction()
        compose.onNodeWithText("中日").performScrollTo().performClick()
        compose.onNodeWithText("繁体显示").performScrollTo().assertIsDisplayed()
        capture("reader-preferences-language")
        compose.onNodeWithText("翻页").performClick()
        compose.onNodeWithText("电子纸阅读模式").assertIsDisplayed()
        assertTrue(compose.onNodeWithText("电子纸阅读模式").getUnclippedBoundsInRoot().top <
            compose.onNodeWithText("正文翻页").getUnclippedBoundsInRoot().top)
        capture("reader-preferences-paging")
        compose.onNodeWithText("显示翻页按钮").performScrollTo().performClick()
        compose.onNodeWithText("章节末尾按钮").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(settings.showPageButtons); assertFalse(settings.showScrollPageButtons) }
        compose.onNodeWithText("更多").performClick()
        capture("reader-preferences-more")
        compose.onNodeWithText("语言与译文").assertDoesNotExist()
        compose.onNodeWithText("离线预读").performScrollTo().performClick()
        compose.onNodeWithText("显示语言").assertDoesNotExist()
        compose.onNodeWithText("关闭").performScrollTo().performClick()
        compose.onNodeWithText("仅 Wi-Fi 自动预读").assertDoesNotExist()
        compose.onNodeWithText("常用").performClick()
        compose.onNodeWithText("中日").performScrollTo().assertIsSelected()
        compose.runOnIdle { assertEquals("zh-jp", settings.mode); assertEquals(0, settings.prefetchChapters) }
    }

    @Test fun choiceGroupsKeepTargetsSeparatedWhenTheFontGrows() {
        val themes = listOf("跟随应用", "纸张", "浅色", "深色", "黑白")
        var selected by mutableIntStateOf(3)
        var dark by mutableStateOf(true)
        var fontScale by mutableFloatStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                NoveliaTheme(if(dark) "dark" else "light") {
                    Surface(Modifier.width(360.dp)) {
                        Column {
                            ChoiceRow("阅读主题", themes, selected) { selected = it }
                            ChoiceRow("分类", listOf("全部", "异世界转生/转移", "现实世界恋爱"), 0) {}
                        }
                    }
                }
            }
        }
        fun checkTargets() {
            val bounds = themes.map { title ->
                val node = compose.onNodeWithText(title)
                node.assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                node.getUnclippedBoundsInRoot()
            }
            bounds.forEachIndexed { index, a ->
                bounds.drop(index + 1).forEach { b ->
                    assertTrue("选项触控区域之间需要留白", b.left - a.right >= 8.dp || a.left - b.right >= 8.dp ||
                        b.top - a.bottom >= 8.dp || a.top - b.bottom >= 8.dp)
                }
            }
            themes.forEach { title ->
                compose.onNodeWithText(title, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { getLayout ->
                    val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    getLayout(results)
                    assertFalse("主题文字不应被裁切", results.single().hasVisualOverflow)
                }
            }
        }
        checkTargets()
        capture("choice-groups-dark")
        compose.runOnIdle { dark = false }
        capture("choice-groups-light")
        compose.runOnIdle { fontScale = 2f }
        checkTargets()
        capture("choice-groups-large-font")
        compose.onNodeWithText("纸张").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(1, selected) }
    }

    @Test fun commentBlockLivesInMoreMenuAndUndoRestoresTheUser() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NoveliaApplication
        runBlocking { app.initialization.await() }
        val previous = app.store.state.value
        val username = "屏蔽撤销测试用户"
        try {
            app.store.update { it.copy(blockedUsers = it.blockedUsers - username) }
            compose.setContent { NoveliaTheme("light") {
                val nav = rememberNavController()
                val scope = rememberCoroutineScope()
                val feedback = remember { SnackbarHostState() }
                val controller = remember { AppController(app, nav, scope, feedback) }
                val state by app.store.state.collectAsStateWithLifecycle()
                Column {
                    if(username !in state.blockedUsers) CommentMoreMenu(username, false, {},
                        onBlock = { scope.launch { blockCommentUser(controller, feedback, username) } })
                    SnackbarHost(feedback)
                }
            } }
            compose.onNodeWithText("屏蔽用户").assertDoesNotExist()
            compose.onNodeWithContentDescription("$username 的评论更多操作").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
            compose.onNodeWithText("屏蔽用户").performClick()
            compose.onNodeWithContentDescription("$username 的评论更多操作").assertDoesNotExist()
            compose.runOnIdle { assertTrue(username in app.store.state.value.blockedUsers) }
            compose.onNodeWithText("撤销").performClick()
            compose.onNodeWithContentDescription("$username 的评论更多操作").assertIsDisplayed()
            compose.runOnIdle { assertFalse(username in app.store.state.value.blockedUsers) }
        } finally { app.store.update { previous } }
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir("ux-screenshots"), "$name.png").outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
