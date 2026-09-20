package cc.novelia.app.reader

import cc.novelia.app.data.model.Chapter
import cc.novelia.app.data.model.ReaderSettings
import com.ibm.icu.text.Transliterator

data class TextPart(val text: String, val source: String, val secondary: Boolean = false)
data class ReadingParagraph(val index: Int, val parts: List<TextPart>, val fallback: Boolean = false, val imageUrl: String? = null, val localImageId: String? = null)

private val localImageMarker = Regex("novelia-image:([a-f0-9]{64})")

fun projectParagraphs(chapter: Chapter, settings: ReaderSettings, checkCancelled: () -> Unit = {}): List<ReadingParagraph> {
    val engines = mapOf("sakura" to chapter.sakuraParagraphs, "gpt" to chapter.gptParagraphs, "youdao" to chapter.youdaoParagraphs)
    val count = maxOf(chapter.paragraphs.size, engines.values.maxOfOrNull { it?.size ?: 0 } ?: 0)
    val selectedEngines = settings.engines.distinct().mapNotNull { engine -> engines[engine]?.let { engine to it } }
    return buildList(count) { for(i in 0 until count) {
        checkCancelled()
        val jp = chapter.paragraphs.getOrNull(i).orEmpty()
        if(jp.startsWith("<图片>")) {
            val url = jp.removePrefix("<图片>").trim()
            val valid = runCatching { java.net.URI(url).let { (it.scheme == "https" || it.scheme == "http") && it.host != null } }.getOrDefault(false)
            add(if(valid) ReadingParagraph(i, emptyList(), imageUrl = url) else ReadingParagraph(i, listOf(TextPart("插图地址不可用", "插图"))))
            continue
        }
        val imageId = localImageMarker.matchEntire(jp)?.groupValues?.get(1)
        if(imageId != null) {
            add(ReadingParagraph(i, listOf(TextPart(jp, "插图")), localImageId = imageId))
            continue
        }
        val translations = if(settings.mode == "jp") emptyList() else buildList {
            for((engine, texts) in selectedEngines) {
                val text = texts.getOrNull(i)?.takeIf(String::isNotBlank) ?: continue
                add(TextPart(text, engine))
                if(!settings.parallel) break
            }
        }
        val missing = translations.isEmpty() && settings.mode != "jp"
        val translated = translations.ifEmpty { listOf(TextPart(jp, "原文 · 暂无译文")) }
        val parts = when(settings.mode) {
            "jp" -> listOf(TextPart(jp, "日文"))
            "jp-zh" -> listOf(TextPart(jp, "日文")) + if(missing) emptyList() else translated.map { it.copy(secondary = true) }
            "zh-jp" -> translated + if(missing) emptyList() else listOf(TextPart(jp, "日文", true))
            else -> translated
        }
        val visibleParts = parts.filter { it.text.isNotBlank() }
        if(visibleParts.isNotEmpty()) add(ReadingParagraph(i, visibleParts, missing))
    } }
}

/** Prepare display text once per chapter/language change, away from composition and scrolling. */
fun prepareReadingParagraphs(chapter: Chapter, settings: ReaderSettings, checkCancelled: () -> Unit = {}): List<ReadingParagraph> {
    // ICU converters are mutable, so each preparation owns its converter instead of sharing one across threads.
    val converter by lazy { Transliterator.getInstance("Simplified-Traditional") }
    return projectParagraphs(chapter, settings, checkCancelled).map { paragraph ->
        checkCancelled()
        if(paragraph.imageUrl != null || paragraph.localImageId != null) paragraph
        else paragraph.copy(parts = paragraph.parts.map { part ->
            checkCancelled()
            val text = part.text.trim()
            val prepared = if(settings.traditional && part.source != "日文" && !part.source.startsWith("原文")) {
                // Bound each ICU operation so leaving a very long chapter cancels promptly.
                buildString(text.length) {
                    var start = 0
                    while(start < text.length) {
                        checkCancelled()
                        var end = (start + 4096).coerceAtMost(text.length)
                        if(end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
                        append(converter.transliterate(text.substring(start, end)))
                        start = end
                    }
                }
            } else text
            part.copy(text = prepared)
        })
    }
}

/** [after] is a projected paragraph offset, not its original source index. Wrap once at the end. */
fun findNextReadingParagraph(paragraphs: List<ReadingParagraph>, query: String, after: Int): Int {
    val term = query.trim()
    if(term.isEmpty() || paragraphs.isEmpty()) return -1
    val start = (after + 1).coerceIn(0, paragraphs.size)
    fun matches(index: Int) = paragraphs[index].let { paragraph ->
        paragraph.imageUrl == null && paragraph.localImageId == null && paragraph.parts.any { it.text.contains(term, ignoreCase = true) }
    }
    for(index in start until paragraphs.size) if(matches(index)) return index
    for(index in 0 until start) if(matches(index)) return index
    return -1
}
