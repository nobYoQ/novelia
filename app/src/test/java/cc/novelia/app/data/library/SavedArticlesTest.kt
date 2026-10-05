package cc.novelia.app.data.library

import cc.novelia.app.data.auth.SessionChangedException
import cc.novelia.app.data.model.Article
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.network.ApiException
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SavedArticlesTest {
    @Test fun refreshUpdatesStatusesAndCountsForBothForumAndLegacyBookmarks() = runBlocking {
        val snapshots = listOf(Article(id = "f-8", title = "旧标题", pinned = true), Article(id = "legacy", locked = true))
        val refreshed = refreshSavedArticleSnapshots(snapshots) { id ->
            Article(id = "server-id", title = "新标题 $id", locked = id == "f-8", hidden = id == "legacy", numComments = 42)
        }
        val state = LibraryState(savedArticles = snapshots).withRefreshedSavedArticles(snapshots, refreshed)
        assertEquals(listOf("f-8", "legacy"), state.savedArticles.map { it.id })
        assertTrue(state.savedArticles.first().locked)
        assertFalse(state.savedArticles.first().pinned)
        assertTrue(state.savedArticles.last().hidden)
        assertFalse(state.savedArticles.last().locked)
        assertTrue(state.savedArticles.all { it.numComments == 42 && it.title.startsWith("新标题") })
    }

    @Test fun deletedOfflineAndUnauthorizedResponsesPreserveBookmarksWithDistinctStates() = runBlocking {
        val snapshots = listOf("deleted", "offline", "login").map { Article(id = it) }
        val refreshed = refreshSavedArticleSnapshots(snapshots) { id ->
            when(id) { "deleted" -> throw ApiException(404, "不可访问"); "offline" -> throw IOException("离线"); else -> throw ApiException(401, "需要登录") }
        }
        assertEquals(listOf(SavedArticleFreshness.UNAVAILABLE, SavedArticleFreshness.UNVERIFIED, SavedArticleFreshness.UNVERIFIED), refreshed.map { it.freshness })
        assertEquals(snapshots, LibraryState(savedArticles = snapshots).withRefreshedSavedArticles(snapshots, refreshed).savedArticles)
    }

    @Test fun slowRefreshCannotResurrectRemovedBookmarksOrOverwriteNewerDetails() {
        val snapshots = listOf(Article(id = "removed"), Article(id = "newer"), Article(id = "refresh"))
        val newer = snapshots[1].copy(title = "后来读取的详情", locked = true, numComments = 99)
        val current = LibraryState(savedArticles = listOf(newer, snapshots[2]), drafts = mapOf("draft" to "保留"))
        val refreshed = snapshots.map { SavedArticleRefresh(it.copy(title = "较早响应"), SavedArticleFreshness.CURRENT) }
        val merged = current.withRefreshedSavedArticles(snapshots, refreshed)
        assertEquals(listOf("newer", "refresh"), merged.savedArticles.map { it.id })
        assertEquals(newer, merged.savedArticles.first())
        assertEquals("较早响应", merged.savedArticles.last().title)
        assertEquals(current.drafts, merged.drafts)
    }

    @Test fun cancellationAndAccountChangesPropagate() = runBlocking {
        val snapshot = listOf(Article(id = "f-8"))
        assertTrue(runCatching { refreshSavedArticleSnapshots(snapshot) { throw CancellationException("取消") } }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { refreshSavedArticleSnapshots(snapshot) { throw SessionChangedException() } }.exceptionOrNull() is SessionChangedException)
    }

    @Test fun pageRefreshLimitsParallelRequests() = runBlocking {
        val active = AtomicInteger(); val maximum = AtomicInteger()
        val snapshots = (1..12).map { Article(id = "f-$it") }
        val refreshed = refreshSavedArticleSnapshots(snapshots) { id ->
            val running = active.incrementAndGet()
            maximum.updateAndGet { maxOf(it, running) }
            try { delay(10); Article(id = id) } finally { active.decrementAndGet() }
        }
        assertEquals(12, refreshed.size)
        assertEquals(4, maximum.get())
        assertEquals(0, active.get())
    }
}
