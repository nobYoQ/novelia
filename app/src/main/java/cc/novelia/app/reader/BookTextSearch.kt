package cc.novelia.app.reader

import cc.novelia.app.data.model.Chapter
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.data.model.TocItem
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class BookTextMatch(val chapterId: String, val chapterLabel: String, val paragraph: Int, val snippet: String,
    val part: Int = 0, val start: Int = 0, val end: Int = 0) {
    val readingMatch get() = ReadingTextMatch(paragraph, part, start, end)
}
data class BookTextSearchResult(val matches: List<BookTextMatch>, val scannedChapters: Int, val availableChapters: Int, val totalChapters: Int, val truncated: Boolean)

/**
 * 用阅读器相同的语言投影和繁体处理搜索全书，结果可直接交给阅读器做精确定位。
 * load 返回 null 表示本章当前不可用，具体是否联网由调用方决定；章节标题分组不计为章节。
 * availableChapters 统计已取得内容的章数，scannedChapters 只统计完整扫描的章节。
 * 达到结果数或字符预算时设置 truncated，界面应提示范围有限，不能把结果解释为全书无遗漏。
 */
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
        val jobContext = currentCoroutineContext()
        val paragraphs = prepareReadingParagraphs(chapter, settings) { jobContext.ensureActive() }
        for((index, paragraph) in paragraphs.withIndex()) {
            currentCoroutineContext().ensureActive()
            if(paragraph.imageUrl != null || paragraph.localImageId != null) continue
            characters += paragraph.parts.sumOf { it.text.length }.toLong()
            if(characters > maxCharacters || matches.size >= maxResults) { truncated = true; break }
            val occurrences = findReadingTextMatches(listOf(paragraph), term, (maxResults - matches.size + 1).coerceAtLeast(0)) { jobContext.ensureActive() }
            for(match in occurrences) {
                if(matches.size >= maxResults) { truncated = true; break }
                val text = paragraph.parts[match.part].text
                val from = (match.start - 35).coerceAtLeast(0)
                val to = (match.end + 65).coerceAtMost(text.length)
                matches += BookTextMatch(id, "第 ${chapterIndex + 1} 章 · ${item.title}", index,
                    (if(from > 0) "…" else "") + text.substring(from, to) + if(to < text.length) "…" else "",
                    match.part, match.start, match.end)
            }
            if(truncated) break
        }
        if(!truncated) scanned++
        else break@chapterLoop
    }
    return BookTextSearchResult(matches, scanned, available, chapters.size, truncated)
}
