package cc.novelia.app

import cc.novelia.app.data.auth.SessionChangedException
import cc.novelia.app.data.library.removeCloudFavorites
import cc.novelia.app.data.library.withCloudFavoritesAddedLocally
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.Position
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.data.network.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CloudFavoriteBatchTest {
    private val web = BookCard(BookRef("syosetu", "n1"), "网络小说", total = 10)
    private val wenku = BookCard(BookRef("wenku", "w1"), "文库小说")

    @Test fun explicitLocalBatchIgnoresAutoCopyPreferenceAndPreservesExistingLibraryData() {
        val saved = SavedBook(web, "我的分组", pinned = true, status = "读完", addedAt = 42)
        val position = Position("c1", 3)
        val before = LibraryState(books = listOf(saved), folders = listOf("默认收藏", "我的分组"),
            positions = mapOf(web.ref.key to position), autoSaveCloudFavoritesLocally = false)
        val result = before.withCloudFavoritesAddedLocally(listOf(web.copy(title = "旧云端标题"), wenku, wenku), "云端导入")
        assertEquals(2, result.books.size)
        assertEquals(saved, result.books.first())
        assertEquals("云端导入", result.books.last().folder)
        assertEquals(position, result.positions[web.ref.key])
        assertTrue("云端导入" in result.folders)
        assertEquals(before.pending, result.pending)
        assertEquals(result, result.withCloudFavoritesAddedLocally(listOf(web, wenku), "云端导入"))
        assertTrue(wenku.ref.key in result.updateSnapshots)
    }

    @Test fun batchUsesCorrectRoutesAndReportsSuccessQueuedAndFailureIndependently() = runBlocking {
        val rejected = web.copy(ref = BookRef("hameln", "2"))
        val attempted = mutableListOf<String>()
        val progress = mutableListOf<Int>()
        val result = removeCloudFavorites(listOf(web, rejected, wenku, web), "all", {}, { path ->
            attempted += path
            when {
                path.endsWith("hameln/2") -> throw ApiException(403, "不允许操作")
                path.contains("favored-wenku") -> true
                else -> false
            }
        }, progress::add)
        assertEquals(listOf("user/favored-web/all/syosetu/n1", "user/favored-web/all/hameln/2", "user/favored-wenku/all/w1"), attempted)
        assertEquals(setOf(web.ref.key), result.removed)
        assertEquals(setOf(wenku.ref.key), result.queued)
        assertEquals(setOf(rejected.ref.key), result.failed.keys)
        assertEquals(listOf(1, 2, 3), progress)
    }

    @Test fun expiredAuthenticationDoesNotKeepSendingOtherBooks() = runBlocking {
        var requests = 0
        val result = removeCloudFavorites(listOf(web, wenku), "default", {}, {
            requests++
            throw ApiException(401, "请重新登录")
        })
        assertEquals(1, requests)
        assertEquals(setOf(web.ref.key, wenku.ref.key), result.failed.keys)
    }

    @Test fun accountChangeStopsTheBatchBeforeAnyRequestForTheNewAccount() = runBlocking {
        var changed = false
        var requests = 0
        try {
            removeCloudFavorites(listOf(web, wenku), "default", { if(changed) throw SessionChangedException() }, {
                requests++; changed = true; false
            })
            fail("会话变化必须中止批处理")
        } catch(expected: SessionChangedException) { assertEquals(1, requests) }
    }

    @Test fun cancellationIsNotReportedAsAnOrdinaryBookFailure() = runBlocking {
        var requests = 0
        try {
            removeCloudFavorites(listOf(web, wenku), "default", {}, {
                requests++
                throw CancellationException("cancelled")
            })
            fail("取消必须继续传播")
        } catch(expected: CancellationException) { assertEquals(1, requests) }
    }
}
