package cc.novelia.app.ui.community

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import cc.novelia.app.MainActivity
import cc.novelia.app.NoveliaApplication
import cc.novelia.app.data.model.Article
import cc.novelia.app.data.model.ForumCategory
import cc.novelia.app.data.model.ForumTag
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.NoveliaTheme
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ForumEditorUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val categories = listOf(ForumCategory(1, "announcements"), ForumCategory(100, "novel", (1L..4).map { ForumTag(it, "标签$it") }), ForumCategory(2, "feedback"))

    private val latestArticle = Article(id = "f-91004", title = "最新帖子标题", content = "最新帖子正文", category = "小说讨论",
        forumCategoryId = 100, forumTags = listOf(ForumTag(2, "标签2")))
    private val oldEdit = ArticleDrafts.snapshot("旧帖子标题", "旧帖子正文", "意见反馈", 2, "feedback", emptyList())

    @Test fun hiddenEditDraftRequiresExplicitChoiceAndCanStillBeRecovered() = withEditor(latestArticle, savedDraft = oldEdit) {
        val app = compose.activity.application as NoveliaApplication
        compose.onNodeWithText("发现本地修改草稿").assertIsDisplayed()
        compose.onNodeWithTag("article-title").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertEquals(oldEdit, app.store.state.value.drafts["article:${latestArticle.id}"]) }
        compose.onNodeWithText("继续本地草稿").performClick()
        compose.onNodeWithTag("article-title").performScrollTo().assertTextContains("旧帖子标题")
        compose.onNodeWithTag("article-body").performScrollTo().assertTextContains("旧帖子正文")
        compose.onNodeWithText("意见反馈").performScrollTo().assertIsSelected()
    }

    @Test fun choosingLatestPostDiscardsOldEditAndDoesNotRecreateItOnExit() = withEditor(latestArticle, savedDraft = oldEdit) {
        val app = compose.activity.application as NoveliaApplication
        compose.onNodeWithText("使用最新帖子").performClick()
        compose.onNodeWithTag("article-title").performScrollTo().assertTextContains("最新帖子标题")
        compose.onNodeWithTag("article-body").performScrollTo().assertTextContains("最新帖子正文")
        compose.onNodeWithText("标签2").performScrollTo().assertIsSelected()
        compose.onNodeWithText("预览").performClick()
        compose.onNodeWithText("编辑").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertFalse(app.store.state.value.drafts.containsKey("article:${latestArticle.id}")) }
        compose.runOnUiThread { compose.activity.setContent {} }
        compose.runOnIdle { assertFalse(app.store.state.value.drafts.containsKey("article:${latestArticle.id}")) }
    }

    @Test fun emptyDraftBoxUsesLatestPostAndOpeningEditorDoesNotCreateAnEditDraft() = withEditor(latestArticle) {
        val app = compose.activity.application as NoveliaApplication
        compose.onNodeWithText("发现本地修改草稿").assertDoesNotExist()
        compose.onNodeWithTag("article-title").performScrollTo().assertTextContains("最新帖子标题")
        compose.onNodeWithTag("article-body").performScrollTo().assertTextContains("最新帖子正文")
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertFalse(app.store.state.value.drafts.containsKey("article:${latestArticle.id}")) }
        compose.onNodeWithTag("article-body").performTextReplacement("新输入的正文")
        compose.runOnUiThread { compose.activity.setContent {} }
        compose.runOnIdle {
            val stored = app.store.state.value.drafts.getValue("article:${latestArticle.id}")
            assertEquals("新输入的正文", ArticleDrafts.read("article:${latestArticle.id}", stored).content)
        }
    }

    @Test fun leavingBeforeChoosingDoesNotOverwriteTheOldDraft() = withEditor(latestArticle, savedDraft = oldEdit) {
        val app = compose.activity.application as NoveliaApplication
        compose.onNodeWithText("发现本地修改草稿").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.setContent {} }
        compose.runOnIdle { assertEquals(oldEdit, app.store.state.value.drafts["article:${latestArticle.id}"]) }
    }

    @Test fun editDraftCanBeDeletedFromDraftBoxBeforeOpeningLatestPost() = withEditor(latestArticle, savedDraft = oldEdit, startInDraftBox = true) {
        val app = compose.activity.application as NoveliaApplication
        compose.onNodeWithText("旧帖子标题").assertExists()
        compose.onNodeWithContentDescription("删除草稿 旧帖子标题").performClick()
        compose.onNodeWithText("删除草稿").performClick()
        compose.onNodeWithText("旧帖子标题").assertDoesNotExist()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("发现本地修改草稿").assertDoesNotExist()
        compose.onNodeWithTag("article-title").performScrollTo().assertTextContains("最新帖子标题")
        compose.onNodeWithTag("article-body").performScrollTo().assertTextContains("最新帖子正文")
        compose.runOnUiThread { compose.activity.setContent {} }
        compose.runOnIdle { assertFalse(app.store.state.value.drafts.containsKey("article:${latestArticle.id}")) }
    }

    @Test fun newPostGuidanceRemainsVisibleAfterDismissingTheGeneralReminder() = withEditor(null, noticeDismissed = true) {
        compose.onNodeWithTag("forum-publishing-notice").assertExists()
        compose.onNodeWithText("发帖前请确认").assertIsDisplayed()
        compose.onNodeWithText("求书集中帖", substring = true).assertExists()
        compose.onNodeWithTag("forum-rules-notice").assertDoesNotExist()
        compose.onNodeWithTag("article-title").performScrollTo().performTextReplacement("保留草稿")
        compose.onNodeWithTag("article-body").performScrollTo().performTextReplacement("提示不会遮挡正文输入")
        compose.onNodeWithTag("article-submit").performScrollTo().assertIsEnabled()
    }

    @Test fun guestEditorExcludesAnnouncementsAndCapsSelectedTagsAtThree() = withEditor(Article(id = "f-91001", title = "测试标题", content = "测试正文", forumCategoryId = 100)) {
        compose.onNodeWithText("站务公告").assertDoesNotExist()
        compose.onNodeWithText("发言请遵守《社区守则》").assertExists()
        for(id in 1..3) compose.onNodeWithText("标签$id").performScrollTo().performClick()
        compose.onNodeWithText("标签4").assertIsNotEnabled()
        compose.onNodeWithText("标签2").performScrollTo().performClick()
        compose.onNodeWithText("标签4").assertIsEnabled().performScrollTo().performClick()
        compose.onNodeWithTag("article-submit").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("意见反馈").performScrollTo().performClick()
        compose.onNodeWithText("标签 0 / 3").assertExists()
        compose.onNodeWithTag("article-submit").performScrollTo().assertIsEnabled()
    }

    @Test fun oversizedExistingDraftRemainsEditableAndUsesUnicodeTitleLength() = withEditor(Article(id = "f-91002", title = "旧".repeat(101), content = "文".repeat(20001), forumCategoryId = 100)) {
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

    @Test fun numericOnlyDraftCannotSilentlyBecomeFeedbackAfterIdRemap() = withEditor(null,
        savedDraft = appJson.encodeToString(mapOf("title" to "旧公告草稿", "content" to "保留旧正文", "categoryId" to "2", "tagIds" to "1"))) {
        compose.onNodeWithTag("article-title").performScrollTo().assertTextContains("旧公告草稿")
        compose.onNodeWithTag("article-body").performScrollTo().assertTextContains("保留旧正文")
        compose.onNodeWithText("草稿分类需要重新确认，请选择可发布的分类。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("article-submit").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("意见反馈").performScrollTo().performClick()
        compose.onNodeWithTag("article-submit").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("article-body").performScrollTo().assertTextContains("保留旧正文")
    }

    @Test fun slugDraftRestoresCurrentNovelCategoryAndOnlyAvailableTags() = withEditor(null,
        savedDraft = appJson.encodeToString(mapOf("title" to "小说草稿", "content" to "恢复正文", "categoryId" to "1", "categorySlug" to "novel", "tagIds" to "1,99"))) {
        compose.onNodeWithText("站务公告").assertDoesNotExist()
        compose.onNodeWithText("标签 1 / 3").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("标签1").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("article-submit").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("article-title").performScrollTo().assertTextContains("小说草稿")
        compose.onNodeWithTag("article-body").performScrollTo().assertTextContains("恢复正文")
    }

    @Test fun editingFiltersRemovedTagsAndPreviewKeepsCurrentSelection() = withEditor(
        Article(id = "f-91003", title = "带标签的帖子", content = "测试正文", forumCategoryId = 100,
            forumTags = listOf(ForumTag(2, "标签2"), ForumTag(99, "已移除标签")))) {
        compose.onNodeWithText("标签 1 / 3").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("标签2").assertIsSelected()
        compose.onNodeWithText("小说讨论").performScrollTo().performClick()
        compose.onNodeWithText("标签2").assertIsSelected()
        compose.onNodeWithText("预览").performClick()
        compose.onNodeWithText("标签2").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("已移除标签").assertDoesNotExist()
        compose.onNodeWithText("编辑").performClick()
        compose.onNodeWithText("标签2").performScrollTo().assertIsSelected()
    }

    private fun withEditor(article: Article?, noticeDismissed: Boolean = false, savedDraft: String? = null, startInDraftBox: Boolean = false, check: () -> Unit) {
        val app = compose.activity.application as NoveliaApplication
        runBlocking { app.initialization.await() }
        val key = article?.let { "article:${it.id}" } ?: ArticleDrafts.newKey(true)
        val previous = app.store.state.value.drafts[key]
        val previousNoticeDismissed = app.store.state.value.forumRulesReminderDismissed
        try {
            compose.runOnUiThread {
                app.store.update { it.copy(drafts = if(savedDraft == null) it.drafts - key else it.drafts + (key to savedDraft), forumRulesReminderDismissed = noticeDismissed) }
                compose.activity.setContent {
                    NoveliaTheme("light") {
                        val c = AppController(app, rememberNavController(), rememberCoroutineScope(), remember { SnackbarHostState() })
                        var draftBox by remember { mutableStateOf(startInDraftBox) }
                        if(draftBox) Column { ArticleDraftBox(c) { draftBox = false } }
                        else ArticleEditor(c, article, categories, forum = true, newPostKey = key)
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
