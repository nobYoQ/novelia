package cc.novelia.app.ui.community

import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.novelia.app.MainActivity
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.Article
import cc.novelia.app.data.model.ForumCategory
import cc.novelia.app.data.model.ForumTag
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.NoveliaTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ForumEditorUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val categories = listOf(ForumCategory(2, "announcements"), ForumCategory(1, "novel", (1L..4).map { ForumTag(it, "标签$it") }), ForumCategory(3, "feedback"))

    @Test fun newPostGuidanceRemainsVisibleAfterDismissingTheGeneralReminder() = withEditor(null, noticeDismissed = true) {
        compose.onNodeWithTag("forum-publishing-notice").assertExists()
        compose.onNodeWithText("发帖前请确认").assertIsDisplayed()
        compose.onNodeWithText("求书集中帖", substring = true).assertExists()
        compose.onNodeWithTag("forum-rules-notice").assertDoesNotExist()
        compose.onNodeWithTag("article-title").performScrollTo().performTextReplacement("保留草稿")
        compose.onNodeWithTag("article-body").performScrollTo().performTextReplacement("提示不会遮挡正文输入")
        compose.onNodeWithTag("article-submit").performScrollTo().assertIsEnabled()
    }

    @Test fun guestEditorExcludesAnnouncementsAndCapsSelectedTagsAtThree() = withEditor(Article(id = "f-91001", title = "测试标题", content = "测试正文", forumCategoryId = 1)) {
        compose.onNodeWithText("站务公告").assertDoesNotExist()
        compose.onNodeWithText("发言请遵守《社区守则》").assertExists()
        for(id in 1..3) compose.onNodeWithText("标签$id").performScrollTo().performClick()
        compose.onNodeWithText("标签4").assertIsNotEnabled()
        compose.onNodeWithText("标签2").performScrollTo().performClick()
        compose.onNodeWithText("标签4").assertIsEnabled().performScrollTo().performClick()
        compose.onNodeWithTag("article-submit").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("意见反馈").performScrollTo().performClick()
        compose.onNodeWithText("标签 0 / 3").assertExists()
    }

    @Test fun oversizedExistingDraftRemainsEditableAndUsesUnicodeTitleLength() = withEditor(Article(id = "f-91002", title = "旧".repeat(101), content = "文".repeat(20001), forumCategoryId = 1)) {
        compose.onNodeWithTag("article-submit").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("article-title").performScrollTo().assertTextContains("旧".repeat(101)).performTextReplacement("😀".repeat(100))
        compose.onNodeWithTag("article-body").performScrollTo().assertTextContains("文".repeat(20001)).performTextReplacement("😀正文")
        compose.onNodeWithTag("article-submit").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("预览").performClick()
        compose.onNodeWithText("编辑").performClick()
        compose.onNodeWithTag("article-title").performScrollTo().assertTextContains("😀".repeat(100))
        compose.onNodeWithTag("article-title").performTextReplacement("😀")
        compose.onNodeWithTag("article-submit").performScrollTo().assertIsNotEnabled()
    }

    private fun withEditor(article: Article?, noticeDismissed: Boolean = false, check: () -> Unit) {
        val app = compose.activity.application as NoveliaApplication
        runBlocking { app.initialization.await() }
        val key = article?.let { "article:${it.id}" } ?: ArticleDrafts.newKey(true)
        val previous = app.store.state.value.drafts[key]
        val previousNoticeDismissed = app.store.state.value.forumRulesReminderDismissed
        try {
            compose.runOnUiThread {
                app.store.update { it.copy(drafts = it.drafts - key, forumRulesReminderDismissed = noticeDismissed) }
                compose.activity.setContent {
                    NoveliaTheme("light") {
                        val c = AppController(app, rememberNavController(), rememberCoroutineScope(), remember { SnackbarHostState() })
                        ArticleEditor(c, article, categories, forum = true, newPostKey = key)
                    }
                }
            }
            check()
        } finally {
            compose.runOnUiThread { compose.activity.setContent {} }
            compose.runOnIdle { app.store.update { it.copy(drafts = if(previous == null) it.drafts - key else it.drafts + (key to previous), forumRulesReminderDismissed = previousNoticeDismissed) } }
        }
    }
}
