package cc.novelia.app.data.library

import cc.novelia.app.data.auth.AuthenticationSession
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.WebDetail
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Resolve visible list entries without waiting for every book on the page. */
class CloudBookMetadataLoader(
    private val session: AuthenticationSession,
    private val detail: suspend (BookRef) -> WebDetail,
) {
    private val requests = Semaphore(2)

    suspend fun load(book: BookCard): BookCard {
        if(book.ref.isLocal || book.ref.isWenku) return book
        val binding = session.capture()
        val account = binding.account ?: return book
        return requests.withPermit {
            session.ensureCurrent(binding)
            val resolved = detail(book.ref).card(book.ref, account)
            session.ensureCurrent(binding)
            val reading = resolved.cloudReading?.let {
                it.copy(lastReadAt = book.cloudReading?.takeIf { source -> source.account == account && it.hasHistory }?.lastReadAt)
            }
            book.copy(
                cloudReading = reading,
                updateAt = book.updateAt?.takeIf { it > 0 } ?: resolved.updateAt,
                total = maxOf(book.total, resolved.total),
            )
        }
    }
}
