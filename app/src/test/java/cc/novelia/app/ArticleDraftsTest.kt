package cc.novelia.app

import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.ui.community.ArticleDrafts
import cc.novelia.app.ui.markdown.DraftPersistence
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ArticleDraftsTest {
    private fun snapshot(title: String, content: String) = appJson.encodeToString(mapOf("title" to title, "content" to content, "category" to "Guide"))

    @Test fun multipleNewPostsAndLegacyDraftSurviveReloadWithoutMixingOtherEditors() {
        val firstKey = ArticleDrafts.newKey()
        val secondKey = ArticleDrafts.newKey()
        assertNotEquals(firstKey, secondKey)
        val state = LibraryState(drafts = mapOf(firstKey to snapshot("第一篇", "离线写作一"), secondKey to snapshot("第二篇", "离线写作二"),
            "article:new" to snapshot("旧版草稿", "旧正文"), "article:existing" to snapshot("修改已发布文章", "原文"),
            "comment:article:existing" to "评论内容", "wenku:new" to "文库草稿"))
        val restored = appJson.decodeFromString<LibraryState>(appJson.encodeToString(state))
        val drafts = ArticleDrafts.newPosts(restored.drafts)
        assertEquals(setOf(firstKey, secondKey, "article:new"), drafts.map { it.key }.toSet())
        assertEquals("离线写作一", drafts.single { it.key == firstKey }.content)
        assertEquals("离线写作二", drafts.single { it.key == secondKey }.content)
        assertEquals("Guide", drafts.single { it.key == firstKey }.category)
    }

    @Test fun publishingOneNewPostLeavesOtherDraftsAndCannotResurrectPublishedOne() {
        val first = ArticleDrafts.newKey()
        val second = ArticleDrafts.newKey()
        val text = snapshot("提交帖子", "提交正文")
        var stored = mapOf(first to text, second to snapshot("继续保留", "未发送正文"))
        val persistence = DraftPersistence({ text }) { value -> stored = if(value == null) stored - first else stored + (first to value) }
        persistence.submittedSuccessfully(text)
        persistence.save()
        assertEquals(setOf(second), stored.keys)
        assertEquals("未发送正文", ArticleDrafts.newPosts(stored).single().content)
    }

    @Test fun malformedLegacyDraftStillExposesOriginalTextForRecovery() {
        val recovered = ArticleDrafts.read("article:new", "保留未能解析的原始正文")
        assertEquals("未命名草稿", recovered.displayTitle)
        assertEquals("保留未能解析的原始正文", recovered.content)
        assertEquals("General", recovered.category)
        assertFalse(ArticleDrafts.isNewPostKey("article:new:other-editor"))
    }
}
