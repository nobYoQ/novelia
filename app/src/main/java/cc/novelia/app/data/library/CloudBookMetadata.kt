package cc.novelia.app.data.library

import cc.novelia.app.data.auth.AuthenticationSession
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.WebDetail
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 为可见网络书目逐项补齐章节进度和分类，最多并发两项，无需等整页完成才显示结果。
 * 等待并发许可前捕获账号绑定，许可取得后与响应返回后均复核，防止旧账号结果流入新会话。
 * 本地文件、文库和游客直接沿用已有书目，不为元数据补齐创建本地收藏。
 */
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
                novelType = resolved.novelType,
                attentions = resolved.attentions,
                totalCharacters = resolved.totalCharacters ?: book.totalCharacters,
            )
        }
    }
}
