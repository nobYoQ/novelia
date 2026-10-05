package cc.novelia.app.data.model

import org.junit.Assert.*
import org.junit.Test

class ForumRulesTest {
    private val member = Profile("作者", "member", 0, Long.MAX_VALUE, 42)
    private val admin = member.copy(role = "admin", userId = 7)
    private val article = Article(id = "f-1", createAt = 1000, forumAuthorId = 42)

    @Test fun titleLimitsCountEmojiAsOneCodePointAndTrimEdges() {
        assertNotNull(ForumRules.titleError(" 文 "))
        assertNull(ForumRules.titleError(" 😀文 "))
        assertNull(ForumRules.titleError("😀".repeat(100)))
        assertNotNull(ForumRules.titleError("😀".repeat(101)))
    }

    @Test fun bodyAndCommentLimitsIncludeWhitespaceAndPreserveUnicode() {
        for((comment, limit) in listOf(false to 20000, true to 1000)) {
            assertNull(ForumRules.contentError("😀".repeat(limit), comment))
            assertNotNull(ForumRules.contentError("😀".repeat(limit) + " ", comment))
            assertNotNull(ForumRules.contentError("\n\t ", comment))
            assertNull(ForumRules.contentError("字", comment))
        }
    }

    @Test fun postsAllowOnlyThreeDistinctPositiveTags() {
        val input = ForumPostInput(1, "标题", "正文", listOf(1, 2, 3))
        assertNull(ForumRules.postError(input))
        for(tags in listOf(listOf(1L, 2, 3, 4), listOf(1L, 1), listOf(0L), listOf(-1L))) {
            assertNotNull(ForumRules.postError(input.copy(tagIds = tags)))
        }
    }

    @Test fun announcementPublishingRequiresAdminAndTrustedCanWriteOtherCategories() {
        assertTrue(ForumRules.canPublish(2, admin))
        for(profile in listOf(member, member.copy(role = "trusted"), null)) {
            assertFalse(ForumRules.canSelectCategory(2, profile))
        }
        assertTrue(ForumRules.canPublish(1, member))
        assertTrue(ForumRules.canPublish(1, member.copy(role = "trusted")))
        for(role in listOf("restricted", "banned", "unknown")) assertFalse(ForumRules.canPublish(1, member.copy(role = role)))
        assertFalse(ForumRules.canPublish(1, null))
    }

    @Test fun authorDeletionExpiresButEditingAndAdminDeletionRemainAvailable() {
        assertTrue(ForumRules.canDeletePost(article, member, 2199))
        assertFalse(ForumRules.canDeletePost(article, member, 2200))
        assertFalse(ForumRules.canDeletePost(article, member.copy(userId = 43), 1000))
        assertFalse(ForumRules.canDeletePost(article, null, 1000))
        assertTrue(ForumRules.canDeletePost(article, admin, 100000))
        assertTrue(ForumRules.canEditPost(article, member))
        assertTrue(ForumRules.canEditPost(article, admin))
        assertFalse(ForumRules.canEditPost(article, member.copy(userId = 43)))
        assertFalse(ForumRules.canEditPost(article, member.copy(role = "restricted")))
    }

    @Test fun adminCanModifyOldCommentsButAuthorsCannot() {
        val comment = ForumComment(1, content = "评论", authorId = 42, authorUsername = "作者", status = 0,
            createdAt = "2026-09-15T00:00:00Z", updatedAt = "2026-09-15T00:00:00Z")
        assertTrue(comment.canModify(member, comment.createdEpoch + 1199))
        assertFalse(comment.canModify(member, comment.createdEpoch + 1200))
        assertTrue(comment.canModify(admin, comment.createdEpoch + 86400))
    }
}
