package cc.novelia.app.data.library

import cc.novelia.app.data.auth.SessionChangedException
import cc.novelia.app.data.model.Article
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.network.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class SavedArticleFreshness { CURRENT, UNAVAILABLE, UNVERIFIED }
data class SavedArticleRefresh(val article: Article, val freshness: SavedArticleFreshness)

/** 只核对调用方提供的一页，限制并发；失败保留收藏，账号变化或取消不得提交旧响应。 */
suspend fun refreshSavedArticleSnapshots(snapshots: List<Article>,
    load: suspend (String) -> Article): List<SavedArticleRefresh> = coroutineScope {
    val permits = Semaphore(4)
    snapshots.map { snapshot -> async {
        permits.withPermit {
            try { SavedArticleRefresh(load(snapshot.id).copy(id = snapshot.id), SavedArticleFreshness.CURRENT) }
            catch(error: CancellationException) { throw error }
            catch(error: SessionChangedException) { throw error }
            catch(error: Exception) {
                SavedArticleRefresh(snapshot, if(error is ApiException && error.status in setOf(403, 404, 410))
                    SavedArticleFreshness.UNAVAILABLE else SavedArticleFreshness.UNVERIFIED)
            }
        }
    } }.awaitAll()
}

/** 不恢复已取消的收藏，也不以慢响应覆盖期间已更新的帖子。 */
fun LibraryState.withRefreshedSavedArticles(snapshots: List<Article>, refreshed: List<SavedArticleRefresh>): LibraryState {
    val originals = snapshots.associateBy { it.id }
    val current = refreshed.filter { it.freshness == SavedArticleFreshness.CURRENT }.associateBy { it.article.id }
    return copy(savedArticles = savedArticles.map { saved ->
        current[saved.id]?.article?.takeIf { saved == originals[saved.id] } ?: saved
    })
}
