package cc.novelia.app.sharing

import cc.novelia.app.data.catalog.BookLinks
import cc.novelia.app.data.catalog.SiteLink
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.Note
import java.io.File

internal enum class CardKind { Post, Book, Quote }

internal data class ShareCard(
    val kind: CardKind,
    val title: String,
    val body: String,
    val byline: String,
    val heading: String,
    val url: String?,
    val linkLabel: String,
    val tags: List<String> = emptyList(),
    val cover: Any? = null,
)

/** 本地分卷可以回到已挂载的在线文库；本地总集与文件 ID 仅在本机有效。 */
internal fun bookShareLink(ref: BookRef, parentKey: String? = null, chapter: String? = null): String? {
    val online = if (ref.isOnline) ref else parentKey?.let(BookRef::fromKey)?.takeIf { it.isWenku } ?: return null
    val url = online.url
    if (BookLinks.parse(url) != SiteLink.Book(online)) return null
    if (online.isWebNovel && !chapter.isNullOrBlank()) {
        val target = "$url/$chapter"
        if (BookLinks.parse(target) == SiteLink.Book(online, chapter)) return target
    }
    return url
}

internal fun BookCard.shareCard(description: String, customCover: File?, parentKey: String? = null) = ShareCard(
    CardKind.Book, title, description, authors.filter(String::isNotBlank).joinToString("、"),
    "书籍推荐", bookShareLink(ref, parentKey), "扫码看书籍", tags, customCover ?: cover,
)

internal fun paragraphCard(ref: BookRef, parentKey: String?, chapterId: String, title: String, chapterTitle: String, quote: String): ShareCard {
    val url = bookShareLink(ref, parentKey, chapterId)
    val chapterLink = url?.let(BookLinks::parse) as? SiteLink.Book
    return ShareCard(CardKind.Quote, title.ifBlank { "未命名作品" }, quote, chapterTitle,
        "阅读摘录", url, if (chapterLink?.chapterId != null) "扫码读本章" else "扫码看书籍")
}

internal fun Note.excerptCard(bookTitle: String, chapterTitle: String, parentKey: String?): ShareCard =
    paragraphCard(BookRef.fromKey(key), parentKey, chapterId, bookTitle, chapterTitle, quote)
