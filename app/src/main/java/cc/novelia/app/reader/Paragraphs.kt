package cc.novelia.app.reader

import cc.novelia.app.data.Chapter
import cc.novelia.app.data.ReaderSettings

data class TextPart(val text: String, val source: String, val secondary: Boolean = false)
data class ReadingParagraph(val index: Int, val parts: List<TextPart>, val fallback: Boolean = false, val imageUrl: String? = null)
fun projectParagraphs(chapter: Chapter, settings: ReaderSettings): List<ReadingParagraph> {
    val engines = mapOf("sakura" to chapter.sakuraParagraphs, "gpt" to chapter.gptParagraphs, "youdao" to chapter.youdaoParagraphs)
    val count = maxOf(chapter.paragraphs.size, engines.values.maxOfOrNull { it?.size ?: 0 } ?: 0)
    return (0 until count).map { i ->
        val jp = chapter.paragraphs.getOrNull(i).orEmpty()
        if(jp.startsWith("<图片>")) {
            val url = jp.removePrefix("<图片>").trim()
            val valid = runCatching { java.net.URI(url).let { it.scheme in listOf("https", "http") && it.host != null } }.getOrDefault(false)
            return@map if(valid) ReadingParagraph(i, emptyList(), imageUrl = url) else ReadingParagraph(i, listOf(TextPart("插图地址不可用", "插图")))
        }
        val translations = settings.engines.mapNotNull { engine -> engines[engine]?.getOrNull(i)?.takeIf(String::isNotBlank)?.let { TextPart(it, engine) } }.let { if(settings.parallel) it else it.take(1) }
        val missing = translations.isEmpty() && settings.mode != "jp"
        val translated = translations.ifEmpty { listOf(TextPart(jp, "原文 · 暂无译文")) }
        val parts = when(settings.mode) {
            "jp" -> listOf(TextPart(jp, "日文"))
            "jp-zh" -> listOf(TextPart(jp, "日文")) + if(missing) emptyList() else translated.map { it.copy(secondary = true) }
            "zh-jp" -> translated + if(missing) emptyList() else listOf(TextPart(jp, "日文", true))
            else -> translated
        }
        ReadingParagraph(i, parts.filter { it.text.isNotBlank() }, missing)
    }.filter { it.parts.isNotEmpty() || it.imageUrl != null }
}
