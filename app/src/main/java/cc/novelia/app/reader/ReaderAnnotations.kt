package cc.novelia.app.reader

import cc.novelia.app.data.model.Chapter
import cc.novelia.app.data.model.Note
import cc.novelia.app.data.model.NoteSelection
import cc.novelia.app.data.model.ReaderSettings

/** 与显示顺序无关的来源标识，原文回退和单语/双语切换使用同一身份。 */
internal fun annotationSource(part: TextPart): String = when(part.source) {
    "日文", "原文", "本地正文", "原文 · 暂无译文" -> "original"
    else -> part.source
}

internal fun selectionFor(paragraph: ReadingParagraph, part: Int, start: Int, end: Int): NoteSelection? {
    val text = paragraph.parts.getOrNull(part) ?: return null
    if(start < 0 || end <= start || end > text.text.length) return null
    val source = annotationSource(text)
    return NoteSelection(source, paragraph.parts.take(part).count { annotationSource(it) == source }, start, end)
}

/** 内容更新后只接受仍匹配的范围或唯一摘录，不把失效批注涂到无关文字上。 */
internal fun noteMatch(note: Note, paragraph: ReadingParagraph, displayIndex: Int): ReadingTextMatch? {
    val selection = note.selection?.takeIf { it.valid } ?: return null
    if(note.paragraph != paragraph.index || note.quote.isEmpty()) return null
    val part = paragraph.parts.indices.filter { annotationSource(paragraph.parts[it]) == selection.source }
        .getOrNull(selection.occurrence) ?: return null
    val text = paragraph.parts[part].text
    val start = if(selection.end <= text.length && text.substring(selection.start, selection.end) == note.quote) selection.start
        else text.indexOf(note.quote).takeIf { it >= 0 && text.indexOf(note.quote, it + 1) < 0 } ?: return null
    return ReadingTextMatch(displayIndex, part, start, start + note.quote.length)
}

/** 查看本段的全部已有版本，不改变阅读设置，也不请求新的翻译。 */
internal fun paragraphVersions(chapter: Chapter, sourceIndex: Int, settings: ReaderSettings, checkCancelled: () -> Unit = {}): ReadingParagraph? =
    prepareReadingParagraphs(chapter, settings.copy(mode = "jp-zh", parallel = true,
        engines = listOf("sakura", "gpt", "youdao")), checkCancelled)
        .firstOrNull { it.index == readingSourceAnchor(chapter, sourceIndex) }

internal fun annotationLabel(part: TextPart, occurrence: Int): String = when(part.source) {
    "sakura" -> "Sakura 译文"
    "gpt" -> "GPT 译文"
    "youdao" -> "有道译文"
    "译文" -> "译文 ${occurrence + 1}"
    "日文", "原文 · 暂无译文" -> "日文原文"
    else -> part.source
}
