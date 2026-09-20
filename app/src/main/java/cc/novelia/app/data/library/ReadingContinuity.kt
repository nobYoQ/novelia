package cc.novelia.app.data.library

import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.data.model.TocItem

/** Directory section headings never contribute to a reader-facing chapter number. */
data class ReadingDestination(val chapterId: String, val number: Int, val title: String) {
    val label get() = "第 $number 章 · $title"
}

fun readingDestination(toc: List<TocItem>, chapterId: String?): ReadingDestination? = toc
    .filter { it.chapterId != null }.withIndex().firstOrNull { it.value.chapterId == chapterId }
    ?.let { ReadingDestination(requireNotNull(it.value.chapterId), it.index + 1, it.value.title) }

fun resumeDestination(toc: List<TocItem>, localId: String?, cloudId: String?): ReadingDestination? =
    readingDestination(toc, localId) ?: readingDestination(toc, cloudId)
    ?: toc.firstOrNull { it.chapterId != null }?.let { ReadingDestination(requireNotNull(it.chapterId), 1, it.title) }

fun LibraryState.nextMountedVolume(ref: BookRef): SavedBook? {
    val parentKey = books.firstOrNull { it.book.ref == ref }?.parentWenkuKey ?: return null
    val volumes = shelfGroups(localOnly = false, folder = "全部", query = "", sort = 0)
        .firstOrNull { it.saved.book.ref.key == parentKey }?.volumes ?: return null
    val index = volumes.indexOfFirst { it.book.ref == ref }
    return if(index >= 0) volumes.getOrNull(index + 1) else null
}

/** One manual operation is deliberately bounded; the user can start another range. */
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
