package cc.novelia.app.reader

import cc.novelia.app.data.model.Chapter
import cc.novelia.app.data.model.LocalBilingualGroup
import cc.novelia.app.data.model.ReaderSettings

/** 备份和本地 JSON 同样是外部输入；坏配对不能隐藏、重复或越界读取正文。 */
internal fun validLocalGroups(chapter: Chapter, checkCancelled: () -> Unit = {}): List<LocalBilingualGroup> {
    val used = BooleanArray(chapter.paragraphs.size)
    return chapter.localContent?.groups.orEmpty().filter { group ->
        checkCancelled()
        val parts = listOf(group.original) + group.translations
        val indices = parts.flatten().sorted()
        val valid = group.translations.isNotEmpty() && parts.all { it.isNotEmpty() && it == it.sorted().distinct() } &&
            indices.isNotEmpty() && indices.first() >= 0 && indices.last() < used.size &&
            indices.distinct().size == indices.size && indices.last() - indices.first() + 1 == indices.size &&
            indices.none { used[it] || chapter.paragraphs[it].isBlank() ||
                chapter.paragraphs[it].startsWith("novelia-image:") || chapter.paragraphs[it].startsWith("<图片>") }
        if (valid) indices.forEach { used[it] = true }
        valid
    }
}

internal fun LocalBilingualGroup.startIndex() = minOf(original.first(), translations.minOf { it.first() })

internal fun readingSourceAnchor(chapter: Chapter, sourceIndex: Int): Int =
    validLocalGroups(chapter).firstOrNull { sourceIndex in it.original || it.translations.any { part -> sourceIndex in part } }
        ?.startIndex() ?: sourceIndex

/** 文件中已有的译文独立于在线引擎选择；未知语言正文始终只显示一份。 */
internal fun projectLocalParagraphs(chapter: Chapter, settings: ReaderSettings, checkCancelled: () -> Unit): List<ReadingParagraph> {
    val groups = validLocalGroups(chapter, checkCancelled)
    val starts = groups.associateBy { it.startIndex() }
    val consumed = BooleanArray(chapter.paragraphs.size)
    groups.forEach { group -> (group.original + group.translations.flatten()).forEach { consumed[it] = true } }
    val secondary = chapter.localContent!!.secondary.toSet()
    // 复用普通投影的空段和插图校验，但不传入任何虚构的译文数组。
    val original = projectParagraphs(Chapter(paragraphs = chapter.paragraphs), settings.copy(mode = "jp"), checkCancelled)
    fun text(indices: List<Int>) = indices.joinToString("\n") { chapter.paragraphs[it] }
    return buildList {
        for (paragraph in original) {
            checkCancelled()
            val group = starts[paragraph.index]
            if (group != null) {
                val jp = TextPart(text(group.original), "日文", settings.mode in setOf("jp-zh", "zh-jp"))
                val translations = group.translations.map { TextPart(text(it), "译文") }
                val parts = when (settings.mode) {
                    "jp" -> listOf(jp)
                    "jp-zh" -> listOf(jp) + translations
                    "zh-jp" -> translations + jp
                    else -> translations
                }
                add(ReadingParagraph(paragraph.index, parts))
            } else if (!consumed[paragraph.index]) {
                add(paragraph.copy(parts = paragraph.parts.map {
                    it.copy(source = if (paragraph.index in secondary || settings.mode == "jp") "原文" else "本地正文",
                        secondary = paragraph.index in secondary)
                }))
            }
        }
    }
}
