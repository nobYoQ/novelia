package cc.novelia.app.ui.community

import cc.novelia.app.data.community.ForumCategory
import cc.novelia.app.data.community.ForumPost
import cc.novelia.app.data.community.ForumSort
import cc.novelia.app.data.community.ForumTag
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.SettingsBackup
import cc.novelia.app.data.model.applyTo
import cc.novelia.app.data.model.settingsBackup
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.webdav.WebDavProjection
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ForumOptionsTest {
    private val categories = listOf(ForumCategory(2, "announcements"), ForumCategory(100, "novel"), ForumCategory(3, "feedback"))

    @Test fun defaultCategoryUsesSlugAndFallsBackWhenRemoved() {
        assertEquals("announcements", initialForumCategory(categories, null))
        assertEquals("novel", initialForumCategory(categories, "novel"))
        assertEquals("novel", initialForumCategory(categories.map { it.copy(id = it.id + 1000) }, "novel"))
        assertEquals("announcements", initialForumCategory(categories, "removed"))
        assertEquals("feedback", initialForumCategory(categories.reversed(), "removed"))
    }

    @Test fun postVisibilityPreservesPinsAndPersonalSearchAndBlocking() {
        val posts = listOf(post(1), post(2, 0), post(3, 5), post(4).copy(authorBlocked = true),
            post(5).copy(authorUsername = "屏蔽用户"))
        val blocked = setOf("屏蔽用户")
        assertEquals(listOf(1L, 2L, 3L), visibleForumPosts(posts, "服务器全文匹配", false, blocked).map { it.id })
        assertEquals(listOf(1L, 2L, 3L), visibleForumPosts(posts, "帖子", true, blocked).map { it.id })
        assertEquals(listOf(2L), visibleForumPosts(posts, "帖子2", true, blocked).map { it.id })
        assertTrue(visibleForumPosts(posts, "不存在", true, blocked).isEmpty())
        assertEquals(listOf(2L, 3L), visibleForumPosts(posts.filter { it.pinOrder != null }, "", false, blocked).map { it.id })
    }

    @Test fun defaultFiltersOnlyUseVisibleSortsAndTagsAvailableInTheirOwnCategory() {
        ForumSort.visibleEntries.forEach { assertEquals(it, ForumSort.fromPreference(it.apiValue)) }
        assertEquals(ForumSort.ACTIVE, ForumSort.fromPreference("newest"))
        assertEquals(ForumSort.ACTIVE, ForumSort.fromPreference("unknown"))
        val tagged = listOf(ForumCategory(1, "novel", listOf(ForumTag(10, "书单"))),
            ForumCategory(2, "feedback", listOf(ForumTag(20, "建议"))))
        assertEquals(mapOf("feedback" to 20L), initialForumTagIds(tagged,
            mapOf("novel" to 20L, "feedback" to 20L, "removed" to 10L)))
        assertTrue(initialForumTagIds(tagged, emptyMap()).isEmpty())
    }

    @Test fun legacySettingsKeepCurrentBehaviorAndPreferencesSurviveSerialization() {
        val legacy = appJson.decodeFromString<LibraryState>("{}")
        val oldBackup = appJson.decodeFromString<SettingsBackup>("{}")
        assertNull(legacy.forumDefaultCategory)
        assertFalse(legacy.forumSkipPinned)
        assertNull(oldBackup.forumDefaultCategory)
        assertFalse(oldBackup.forumSkipPinned)
        assertEquals("active", legacy.forumDefaultSort)
        assertEquals("active", oldBackup.forumDefaultSort)
        assertTrue(legacy.forumDefaultTagIds.isEmpty() && oldBackup.forumDefaultTagIds.isEmpty())
        val state = legacy.copy(forumDefaultCategory = "novel", forumSkipPinned = true,
            forumDefaultSort = "comments", forumDefaultTagIds = mapOf("novel" to 41L))
        val restored = appJson.decodeFromString<LibraryState>(appJson.encodeToString(state))
        assertEquals("novel", restored.forumDefaultCategory)
        assertTrue(restored.forumSkipPinned)
        assertEquals(state.forumDefaultSort, restored.forumDefaultSort)
        assertEquals(state.forumDefaultTagIds, restored.forumDefaultTagIds)
        val backup = appJson.decodeFromString<SettingsBackup>(appJson.encodeToString(state.settingsBackup())).applyTo(legacy)
        assertEquals(state.forumDefaultSort, backup.forumDefaultSort)
        assertEquals(state.forumDefaultTagIds, backup.forumDefaultTagIds)
        assertTrue(WebDavProjection.affectedLibraryDomains(legacy, state).isEmpty())
        assertEquals(WebDavProjection.library(legacy), WebDavProjection.library(state))
    }

    private fun post(id: Long, pinOrder: Int? = null) = ForumPost(id, 100, "帖子$id", 42, "读者", 0, 1, 0, false,
        pinOrder = pinOrder, createdAt = "2026-11-11T00:00:00Z", updatedAt = "2026-11-11T00:00:00Z", activeAt = "2026-11-11T00:00:00Z")
}
