package cc.novelia.app.ui.community

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cc.novelia.app.data.model.ForumSort
import cc.novelia.app.data.model.ForumComment
import cc.novelia.app.data.model.ForumStrike
import cc.novelia.app.data.model.Profile
import cc.novelia.app.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ForumAccountUiTest {
    @get:Rule val compose = createComposeRule()
    private val profile = Profile("论坛测试用户", "member", 1600000000, 4102444800, 42)

    @Test fun panelActionsCloseAndBackOnlyDismissesPanel() {
        val actions = mutableListOf<ForumAccountAction>()
        compose.setContent { NoveliaTheme("light") { PanelScene(profile) { actions += it } } }
        compose.onNodeWithTag("forum-account-toggle").performClick()
        compose.onNodeWithText("处罚记录").performScrollTo().performClick()
        compose.onNodeWithTag("forum-account-panel").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(ForumAccountAction.STRIKES), actions) }
        compose.onNodeWithTag("forum-account-toggle").performClick()
        compose.onNodeWithText("我的帖子").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ForumAccountAction.POSTS, actions.last()) }
        compose.onNodeWithTag("forum-account-toggle").performClick()
        compose.onNodeWithText("本地收藏").performScrollTo().assertIsDisplayed()
        pressBack()
        compose.onNodeWithTag("forum-account-panel").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, actions.size) }
    }

    @Test fun sortPickerSelectsAllFourPresets() {
        var sort by mutableStateOf(ForumSort.ACTIVE)
        compose.setContent { NoveliaTheme("light") { Box(Modifier.padding(top = 48.dp)) { ForumSortPicker(sort) { sort = it } } } }
        for(option in listOf(ForumSort.NEWEST, ForumSort.VIEWS, ForumSort.COMMENTS, ForumSort.ACTIVE)) {
            compose.onNodeWithTag("forum-sort").performClick()
            compose.onNodeWithText(option.label).performClick()
            compose.runOnIdle { assertEquals(option, sort) }
            compose.onNodeWithTag("forum-sort").assertTextContains(option.label)
        }
    }

    @Test fun panelWorksInBothThemesAndLargeTextWithReducedMotion() {
        var theme by mutableStateOf("light")
        var large by mutableStateOf(false)
        var reduced by mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if(large) 1.6f else 1f), LocalReducedMotion provides reduced) {
                NoveliaTheme(theme) { PanelScene(profile) {} }
            }
        }
        compose.onNodeWithTag("forum-account-toggle").performClick()
        compose.onNodeWithText("处罚记录").performScrollTo().assertIsDisplayed()
        screenshot("forum-panel-light")
        compose.runOnIdle { theme = "dark" }
        compose.onNodeWithText("处罚记录").assertIsDisplayed()
        screenshot("forum-panel-dark")
        compose.runOnIdle { large = true; reduced = true }
        compose.onNodeWithText("退出论坛登录").performScrollTo().assertIsDisplayed()
        screenshot("forum-panel-large")
        pressBack()
        compose.onNodeWithTag("forum-account-panel").assertDoesNotExist()
        compose.onNodeWithTag("forum-account-toggle").performClick()
        compose.onNodeWithText("处罚记录").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("收起我的论坛").performScrollTo().performClick()
        compose.onNodeWithTag("forum-account-panel").assertDoesNotExist()
    }

    @Test fun guestCanLoginAndStrikeCardsShowEvidenceAndRevocation() {
        var action: ForumAccountAction? = null
        compose.setContent { NoveliaTheme("light") { PanelScene(null) { action = it } } }
        compose.onNodeWithTag("forum-account-toggle").performClick()
        compose.onNodeWithText("登录论坛").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ForumAccountAction.LOGIN, action) }
        compose.onNodeWithTag("forum-account-panel").assertDoesNotExist()
        compose.onNodeWithText("已撤销").assertIsDisplayed()
        compose.onNodeWithText("测试处罚依据").assertIsDisplayed()
    }

    @Test fun rulesAreAvailableToGuestsAndCloseThePanel() {
        var action: ForumAccountAction? = null
        compose.setContent { NoveliaTheme("light") { PanelScene(null) { action = it } } }
        compose.onNodeWithTag("forum-account-toggle").performClick()
        compose.onNodeWithText("社区守则").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ForumAccountAction.RULES, action) }
        compose.onNodeWithTag("forum-account-panel").assertDoesNotExist()
    }

    @Test fun onlyAdminCanExpandModeratedContentAndAccountChangeCollapsesIt() {
        var viewer by mutableStateOf<Profile?>(profile.copy(role = "admin"))
        var comment by mutableStateOf(ForumComment(1, content = "管理员可见原文", authorId = 42, authorUsername = "读者", status = 1,
            createdAt = "2026-09-15T00:00:00Z", updatedAt = "2026-09-15T00:00:00Z"))
        compose.setContent { NoveliaTheme("light") { Column { ForumCommentContent(comment, viewer) { Text(it) } } } }
        compose.onNodeWithText("管理员可见原文").assertDoesNotExist()
        compose.onNodeWithText("该评论已隐藏 · 查看原文").performClick()
        compose.onNodeWithText("管理员可见原文").assertIsDisplayed()
        compose.runOnIdle { viewer = profile }
        compose.onNodeWithText("管理员可见原文").assertDoesNotExist()
        compose.onNodeWithText("该评论已隐藏").assertIsDisplayed()
        compose.runOnIdle { viewer = profile.copy(role = "admin"); comment = comment.copy(status = 2) }
        compose.onNodeWithText("管理员可见原文").assertDoesNotExist()
        compose.onNodeWithText("该评论已删除 · 查看原文").performClick()
        compose.onNodeWithText("管理员可见原文").assertIsDisplayed()
        compose.onNodeWithText("该评论已删除 · 收起原文").performClick()
        compose.onNodeWithText("管理员可见原文").assertDoesNotExist()
    }

    @Composable private fun PanelScene(user: Profile?, action: (ForumAccountAction) -> Unit) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("社区", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    ForumAccountMenu(user, action)
                }
                HorizontalDivider()
                Text("站务公告   小说讨论   意见反馈", Modifier.padding(20.dp), style = MaterialTheme.typography.titleSmall)
                ForumStrikeCard(ForumStrike(2, "测试处罚记录", "测试处罚依据", 1, "2026-09-15T00:00:00Z", "2026-09-15T02:00:00Z"))
            }
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val suffix = InstrumentationRegistry.getArguments().getString("forumVariant") ?: "portrait"
        val directory = File(instrumentation.targetContext.getExternalFilesDir("screenshots"), "forum").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(directory, "$name-$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun pressBack() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }
}
