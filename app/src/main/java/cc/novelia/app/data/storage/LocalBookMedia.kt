package cc.novelia.app.data.storage

import cc.novelia.app.data.model.BookMedium
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.bookMedium
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 为升级或备份恢复的旧书目一次性补齐摘要；不加载图片，不覆盖并发导入或用户整理的结果。 */
internal suspend fun LocalStore.restoreBookMedia(refs: Set<BookRef>) = withContext(Dispatchers.IO) {
    val media = mutableMapOf<BookRef, BookMedium>()
    for (ref in refs) {
        ensureActive()
        try {
            media[ref] = documentIndex(ref.id).bookMedium { chapter ->
                ensureActive()
                documentChapter(ref.id, chapter)
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* 原件或索引不可读时保留现有摘要，以便日后恢复。 */ }
    }
    ensureActive()
    if (media.isNotEmpty()) update { state ->
        state.copy(books = state.books.map { saved ->
            val medium = media[saved.book.ref]
            if (medium != null && saved.book.medium == null) saved.copy(book = saved.book.copy(medium = medium)) else saved
        })
    }
}
