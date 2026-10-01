package cc.novelia.app.data.library

import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.data.model.TocItem
import cc.novelia.app.data.model.withKnownChapter

/** 目录分组标题不计入向读者显示的章节序号。 */
data class ReadingDestination(val chapterId: String, val number: Int, val title: String) {
    val label get() = "第 $number 章 · $title"
}

fun readingDestination(toc: List<TocItem>, chapterId: String?): ReadingDestination? = toc
    .filter { it.chapterId != null }.withIndex().firstOrNull { it.value.chapterId == chapterId }
    ?.let { ReadingDestination(requireNotNull(it.value.chapterId), it.index + 1, it.value.title) }

/** 依次尝试仍在目录中的本地章节、云端章节和首个可读章节；分组标题不是可阅读目的地。 */
fun resumeDestination(toc: List<TocItem>, localId: String?, cloudId: String?): ReadingDestination? =
    readingDestination(toc, localId) ?: readingDestination(toc, cloudId)
    ?: toc.firstOrNull { it.chapterId != null }?.let { ReadingDestination(requireNotNull(it.chapterId), 1, it.title) }

/** 公开封面元数据也供游客使用；云端阅读进度仍须绑定当前账号。 */
fun LibraryState.withCloudReadingMetadata(cards: List<BookCard>, account: String?): LibraryState {
    val metadata = cards.associateBy { it.ref.key }
    if(metadata.isEmpty()) return this
    return copy(books = books.map { saved ->
        metadata[saved.book.ref.key]?.let { incoming -> saved.copy(book = saved.book.copy(
            cloudReading = incoming.cloudReading?.takeIf { account != null && it.account == account }
                ?.withKnownChapter(saved.book.cloudReading) ?: saved.book.cloudReading,
            updateAt = if(account != null && incoming.cloudReading?.account == account)
                incoming.updateAt?.takeIf { it > 0 } ?: saved.book.updateAt else saved.book.updateAt,
            novelType = incoming.novelType?.takeIf { it.isNotBlank() } ?: saved.book.novelType,
            attentions = incoming.attentions ?: saved.book.attentions,
        )) } ?: saved
    })
}

/** 按书架当前分卷顺序继续阅读，只选择同一文库父书目下已挂载的下一份本地文件。 */
fun LibraryState.nextMountedVolume(ref: BookRef): SavedBook? {
    val parentKey = books.firstOrNull { it.book.ref == ref }?.parentWenkuKey ?: return null
    val volumes = shelfGroups(localOnly = false, folder = "全部", query = "", sort = 0)
        .firstOrNull { it.saved.book.ref.key == parentKey }?.volumes ?: return null
    val index = volumes.indexOfFirst { it.book.ref == ref }
    return if(index >= 0) volumes.getOrNull(index + 1) else null
}

/** 单次手动操作限制处理范围，用户可另选下一批继续。 */
fun chapterCacheRange(toc: List<TocItem>, first: Int, last: Int): List<String> {
    val chapters = toc.mapNotNull { it.chapterId }.distinct()
    require(first in 1..chapters.size && last in first..chapters.size) { "请输入目录范围内的起止章节" }
    require(last - first < 200) { "每次最多缓存 200 章，请分批选择" }
    return chapters.subList(first - 1, last)
}

fun offlineRangeLabel(toc: List<TocItem>, cachedIds: Set<String>): String {
    val numbers = toc.mapNotNull { it.chapterId }.mapIndexedNotNull { index, id -> (index + 1).takeIf { id in cachedIds } }
    if(numbers.isEmpty()) return "暂无离线章节"
    val groups = mutableListOf<String>()
    var start = numbers.first()
    var last = start
    fun append() { groups += if(start == last) "$start" else "$start–$last" }
    for(number in numbers.drop(1)) { if(number == last + 1) last = number else { append(); start = number; last = number } }
    append()
    return "已缓存 ${numbers.size}/${toc.count { it.chapterId != null }} 章 · 第 ${groups.take(6).joinToString("、")}${if(groups.size > 6) "…" else ""} 章"
}
