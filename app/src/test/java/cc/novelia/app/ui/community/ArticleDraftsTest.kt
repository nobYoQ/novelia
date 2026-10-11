package cc.novelia.app.ui.community

import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.community.ForumCategory
import cc.novelia.app.data.community.ForumTag
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.ui.markdown.DraftPersistence
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ArticleDraftsTest {
    private fun snapshot(title: String, content: String) = appJson.encodeToString(mapOf("title" to title, "content" to content, "category" to "Guide"))

    @Test fun draftBoxIncludesExistingPostEditsAndExcludesOtherEditors() {
        val newPost = ArticleDrafts.newKey(true)
        val text = snapshot("旧帖子内容", "未提交的修改")
        val stored = mapOf(newPost to text, "article:f-10" to text, "article:legacy-id" to text,
            "article:new" to text, "article:forum-new" to text,
            "comment:article:f-10" to "评论", "wenku:new" to "文库", "updates:last" to "进度",
            "article:" to text, "article:new:invalid" to text, "article:forum-new:invalid" to text)
        val visible = ArticleDrafts.posts(stored)
        assertEquals(setOf(newPost, "article:f-10", "article:legacy-id", "article:new", "article:forum-new"), visible.map { it.key }.toSet())
        assertEquals("f-10", ArticleDrafts.editedArticleId("article:f-10"))
        assertEquals("legacy-id", ArticleDrafts.editedArticleId("article:legacy-id"))
        assertNull(ArticleDrafts.editedArticleId(newPost))
        assertNull(ArticleDrafts.editedArticleId("article:forum-new"))
        assertNull(ArticleDrafts.editedArticleId("comment:article:f-10"))
        assertTrue(ArticleDrafts.posts(stored - visible.map { it.key }.toSet()).isEmpty())
    }

    @Test fun multipleNewPostsAndLegacyDraftSurviveReloadWithoutMixingOtherEditors() {
        val firstKey = ArticleDrafts.newKey()
        val secondKey = ArticleDrafts.newKey()
        assertNotEquals(firstKey, secondKey)
        val state = LibraryState(drafts = mapOf(firstKey to snapshot("第一篇", "离线写作一"), secondKey to snapshot("第二篇", "离线写作二"),
            "article:new" to snapshot("旧版草稿", "旧正文"), "article:existing" to snapshot("修改已发布文章", "原文"),
            "comment:article:existing" to "评论内容", "wenku:new" to "文库草稿"))
        val restored = appJson.decodeFromString<LibraryState>(appJson.encodeToString(state))
        val drafts = ArticleDrafts.posts(restored.drafts)
        assertEquals(setOf(firstKey, secondKey, "article:new", "article:existing"), drafts.map { it.key }.toSet())
        assertEquals("离线写作一", drafts.single { it.key == firstKey }.content)
        assertEquals("离线写作二", drafts.single { it.key == secondKey }.content)
        assertEquals("Guide", drafts.single { it.key == firstKey }.category)
    }

    @Test fun publishingOneNewPostLeavesOtherDraftsAndCannotResurrectPublishedOne() {
        val first = ArticleDrafts.newKey()
        val second = ArticleDrafts.newKey()
        val text = snapshot("提交帖子", "提交正文")
        var stored = mapOf(first to text, second to snapshot("继续保留", "未发送正文"))
        val persistence = DraftPersistence({ text }, { value -> stored = if(value == null) stored - first else stored + (first to value) })
        persistence.submittedSuccessfully(text)
        persistence.save()
        assertEquals(setOf(second), stored.keys)
        assertEquals("未发送正文", ArticleDrafts.posts(stored).single().content)
    }

    @Test fun forumDraftsKeepTheirSiteCategoryAndTagsAcrossReload() {
        val first = ArticleDrafts.newKey(forum = true)
        val second = ArticleDrafts.newKey(forum = true)
        val legacy = ArticleDrafts.newKey()
        val forumText = appJson.encodeToString(mapOf("title" to "论坛草稿", "content" to "论坛正文",
            "categoryId" to "3", "tagIds" to "7,9"))
        val state = LibraryState(drafts = mapOf(first to forumText, second to forumText,
            "article:forum-new" to forumText, legacy to snapshot("旧站草稿", "旧站正文"),
            "article:f-10" to forumText, "forum-comment:10:guest:root" to "评论"))
        val restored = appJson.decodeFromString<LibraryState>(appJson.encodeToString(state))
        val drafts = ArticleDrafts.posts(restored.drafts)
        assertEquals(setOf(first, second, "article:forum-new", legacy, "article:f-10"), drafts.map { it.key }.toSet())
        assertNotEquals(first, second)
        val recovered = drafts.single { it.key == first }
        assertEquals(3L, recovered.categoryId)
        assertEquals(listOf(7L, 9L), recovered.tagIds)
        assertEquals("论坛正文", recovered.content)
        assertTrue(ArticleDrafts.isForumNewPostKey(first))
        assertTrue(ArticleDrafts.isForumNewPostKey("article:forum-new"))
        assertFalse(ArticleDrafts.isForumNewPostKey(legacy))
        assertFalse(ArticleDrafts.isNewPostKey("article:forum-new:other-editor"))
    }

    @Test fun malformedLegacyDraftStillExposesOriginalTextForRecovery() {
        val recovered = ArticleDrafts.read("article:new", "保留未能解析的原始正文")
        assertEquals("未命名草稿", recovered.displayTitle)
        assertEquals("保留未能解析的原始正文", recovered.content)
        assertEquals("General", recovered.category)
        assertFalse(ArticleDrafts.isNewPostKey("article:new:other-editor"))
    }

    @Test fun numericOnlyForumDraftsKeepTextAndRequireCategoryConfirmation() {
        val categories = listOf(ForumCategory(1, "announcements"), ForumCategory(2, "feedback"), ForumCategory(100, "novel"))
        for(oldId in listOf(1, 2, 3, 100)) {
            val text = appJson.encodeToString(mapOf("title" to "保留标题", "content" to "保留正文", "categoryId" to oldId.toString(), "tagIds" to "7,9"))
            val draft = ArticleDrafts.read("article:forum-new", text)
            assertEquals("保留标题", draft.title)
            assertEquals("保留正文", draft.content)
            assertNull(draft.forumCategory(categories))
            assertTrue(draft.forumTags(categories).isEmpty())
        }
    }

    @Test fun forumDraftsRestoreBySlugAndValidateTagsAgainstCurrentCategories() {
        val categories = listOf(ForumCategory(1, "announcements"), ForumCategory(2, "feedback"),
            ForumCategory(100, "novel", listOf(ForumTag(7, "有效标签"))))
        val text = appJson.encodeToString(mapOf("title" to "保留标题", "content" to "保留正文",
            "categoryId" to "1", "categorySlug" to "novel", "tagIds" to "7,9,7"))
        val draft = ArticleDrafts.read("article:forum-new", text)
        assertEquals(100L, draft.forumCategory(categories)?.id)
        assertEquals(listOf(7L), draft.forumTags(categories))
        assertNull(draft.forumCategory(categories.filter { it.slug != "novel" }))
        assertTrue(draft.forumTags(categories.filter { it.slug != "novel" }).isEmpty())
        assertEquals("保留正文", draft.content)
    }
}
