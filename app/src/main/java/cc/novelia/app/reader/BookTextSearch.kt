package cc.novelia.app.reader

import cc.novelia.app.data.Chapter
import cc.novelia.app.data.ReaderSettings
import cc.novelia.app.data.TocItem
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class BookTextMatch(val chapterId: String, val chapterLabel: String, val paragraph: Int, val snippet: String)
data class BookTextSearchResult(val matches: List<BookTextMatch>, val scannedChapters: Int, val availableChapters: Int, val totalChapters: Int, val truncated: Boolean)

/** Searches the exact prepared reader text, so paragraph offsets also work after blank/image filtering. */
suspend fun searchBookText(toc: List<TocItem>, query: String, settings: ReaderSettings,
    load: suspend (String) -> Chapter?, maxResults: Int = 200, maxCharacters: Long = 20_000_000): BookTextSearchResult {
    val chapters = toc.filter { it.chapterId != null }
    val term = query.trim()
    if(term.isEmpty()) return BookTextSearchResult(emptyList(), 0, 0, chapters.size, false)
    val matches = mutableListOf<BookTextMatch>()
    var scanned = 0
    var available = 0
    var characters = 0L
    var truncated = false
    chapterLoop@ for((chapterIndex, item) in chapters.withIndex()) {
        currentCoroutineContext().ensureActive()
        val id = requireNotNull(item.chapterId)
        val chapter = load(id) ?: continue
        available++
        val paragraphs = prepareReadingParagraphs(chapter, settings)
        for((index, paragraph) in paragraphs.withIndex()) {
            currentCoroutineContext().ensureActive()
            if(paragraph.imageUrl != null || paragraph.localImageId != null) continue
            characters += paragraph.parts.sumOf { it.text.length }.toLong()
            if(characters > maxCharacters || matches.size >= maxResults) { truncated = true; break }
            val part = paragraph.parts.firstOrNull { it.text.contains(term, ignoreCase = true) } ?: continue
            val offset = part.text.indexOf(term, ignoreCase = true)
            val from = (offset - 35).coerceAtLeast(0)
            val to = (offset + term.length + 65).coerceAtMost(part.text.length)
            matches += BookTextMatch(id, "第 ${chapterIndex + 1} 章 · ${item.title}", index,
                (if(from > 0) "…" else "") + part.text.substring(from, to) + if(to < part.text.length) "…" else "")
        }
        if(!truncated) scanned++
        else break@chapterLoop
    }
    return BookTextSearchResult(matches, scanned, available, chapters.size, truncated)
}
