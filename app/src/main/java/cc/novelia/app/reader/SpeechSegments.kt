package cc.novelia.app.reader

import cc.novelia.app.data.model.Chapter
import cc.novelia.app.data.model.Note
import cc.novelia.app.data.model.NoteSelection
import cc.novelia.app.data.model.ReaderSettings
import kotlinx.serialization.Serializable

/** 句子保留其原始段落及语言身份，偏移相对于屏幕实际显示的单份文本。 */
@Serializable internal data class SpeechSegment(
    val text: String, val paragraph: Int, val source: String, val occurrence: Int = 0, val start: Int = 0,
) {
    fun match(paragraphs: List<ReadingParagraph>): ReadingTextMatch? {
        val index = paragraphs.indexOfFirst { it.index == paragraph }
        if(index < 0) return null
        return noteMatch(Note("speech", "", "", paragraph, text, "",
            selection = NoteSelection(source, occurrence, start, start + text.length)), paragraphs[index], index)
    }
}

internal fun speechSelection(text: String, paragraph: Int, source: String, occurrence: Int = 0, start: Int = 0,
    checkCancelled: () -> Unit = {}): List<SpeechSegment> = buildList {
    var offset = start + text.length - text.trimStart().length
    for(sentence in prepareSpeechQueue(listOf(text), checkCancelled)) {
        checkCancelled()
        if(sentence.isNotBlank()) add(SpeechSegment(sentence, paragraph, source, occurrence, offset))
        offset += sentence.length
    }
}

internal fun speechSegments(chapter: Chapter, settings: ReaderSettings, startSourceIndex: Int = 0,
    checkCancelled: () -> Unit = {}): List<SpeechSegment> {
    val japanese = settings.speechLanguage == "jp" || (settings.speechLanguage == "auto" && settings.mode.startsWith("jp"))
    return prepareReadingParagraphs(chapter, settings.copy(mode = if(japanese) "jp" else "zh", parallel = false), checkCancelled)
        .filter { it.index >= readingSourceAnchor(chapter, startSourceIndex) && it.imageUrl == null && it.localImageId == null }
        .flatMap { paragraph ->
            checkCancelled()
            paragraph.parts.firstOrNull { !it.secondary }?.let { part ->
                speechSelection(part.text, paragraph.index, annotationSource(part), checkCancelled = checkCancelled)
            }.orEmpty()
        }
}

internal data class SpeechPlayback(
    val session: String, val bookKey: String?, val chapterId: String?, val segment: SpeechSegment?,
    val index: Int, val count: Int, val playing: Boolean, val following: Boolean = true,
) {
    fun canFollow(book: String, resumed: Boolean, blocked: Boolean) = playing && following &&
        bookKey == book && chapterId != null && resumed && !blocked
}
