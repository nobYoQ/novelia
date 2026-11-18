package cc.novelia.app.data.library

import cc.novelia.app.data.auth.SessionChangedException
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.network.ApiException
import cc.novelia.app.data.network.cloudFavoritePath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class CloudFavoriteBatchResult(
    val removed: Set<String>,
    val queued: Set<String>,
    val failed: Map<String, Exception>,
)

/** 使用原有幂等删除与离线队列，单本被拒绝后仍处理其他书；会话变化立即中止。 */
suspend fun removeCloudFavorites(
    books: List<BookCard>,
    folderId: String,
    validate: () -> Unit,
    remove: suspend (path: String) -> Boolean,
    onProgress: (Int) -> Unit = {},
): CloudFavoriteBatchResult {
    val removed = linkedSetOf<String>()
    val queued = linkedSetOf<String>()
    val failed = linkedMapOf<String, Exception>()
    var authenticationError: ApiException? = null
    books.distinctBy { it.ref }.forEachIndexed { index, book ->
        currentCoroutineContext().ensureActive()
        validate()
        try {
            authenticationError?.let { throw it }
            // all 聚合收藏夹也接受按小说删除，网络小说保留 provider/id 两段标识。
            val pending = remove(book.ref.cloudFavoritePath(folderId))
            validate()
            if(pending) queued += book.ref.key else removed += book.ref.key
        } catch(error: CancellationException) {
            throw error
        } catch(error: SessionChangedException) {
            throw error
        } catch(error: Exception) {
            failed[book.ref.key] = error
            if(error is ApiException && error.status == 401) authenticationError = error
        }
        onProgress(index + 1)
    }
    return CloudFavoriteBatchResult(removed, queued, failed)
}
