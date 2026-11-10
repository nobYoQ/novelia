package cc.novelia.app.data.community

import org.junit.Assert.*
import org.junit.Test

class CommentVisibilityTest {
    private val comment = ForumComment(1, content = "评论", authorId = 42, authorUsername = "读者",
        status = 0, createdAt = "2026-09-30T00:00:00Z", updatedAt = "2026-09-30T00:00:00Z")

    @Test fun unavailableCommentsAreOnlyFilteredWhenEnabled() {
        for(status in 0..2) {
            val item = comment.copy(status = status)
            assertTrue(item.visible(emptySet(), false))
            assertEquals(status == 0, item.visible(emptySet(), true))
            assertEquals(status == 0, item.copy(rootId = 9).visible(emptySet(), true))
        }
    }

    @Test fun localAndAccountBlocksApplyRegardlessOfStatusPreference() {
        for(hide in listOf(false, true)) {
            assertFalse(comment.visible(setOf(comment.authorUsername), hide))
            assertFalse(comment.copy(authorBlocked = true).visible(emptySet(), hide))
            assertTrue(comment.visible(setOf("其他用户"), hide))
        }
    }
}
