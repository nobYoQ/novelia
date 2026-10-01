package cc.novelia.app.reader

import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.Chapter
import cc.novelia.app.data.model.ReaderSettings
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable

/** 请求文件只包含正文、设置和无令牌的会话身份；Intent 始终仅传文件 ID。 */
@Serializable internal data class SpeechRequest(
    val paragraphs: List<String>,
    val settings: ReaderSettings,
    val ref: BookRef? = null,
    val chapterId: String? = null,
    val nextId: String? = null,
    val account: String? = null,
    val sessionGeneration: Long = 0
)

internal data class SpeechChapter(val id: String, val title: String, val paragraphs: List<String>)

/** 与 UI 生命周期无关的续章策略：本地文件、章节缓存、允许时联网，依序短路。 */
internal suspend fun resolveSpeechChapter(ref: BookRef, id: String, allowNetwork: Boolean,
    local: suspend (String) -> Chapter, cached: suspend (String) -> Chapter?, network: suspend (String) -> Chapter): Chapter {
    if(ref.isLocal) return local(id)
    cached(id)?.let { return it }
    check(allowNetwork) { "下一章尚未缓存，可先缓存章节或在阅读设置中允许联网续章后重新开始" }
    return network(id)
}

internal fun speechParagraphs(chapter: Chapter, settings: ReaderSettings, startSourceIndex: Int = 0, checkCancelled: () -> Unit = {}): List<String> {
    val japanese = settings.speechLanguage == "jp" || (settings.speechLanguage == "auto" && settings.mode.startsWith("jp"))
    val text = if(japanese) chapter.paragraphs.drop(startSourceIndex.coerceAtLeast(0)) else {
        // 显式选择中文时，无论屏幕语言顺序如何，都采用中文投影及既有译文优先级。
        prepareReadingParagraphs(chapter, settings.copy(mode = "zh"), checkCancelled)
            .filter { it.index >= startSourceIndex && it.imageUrl == null && it.localImageId == null }
            .mapNotNull { it.parts.firstOrNull { part -> !part.secondary }?.text }
    }
    return prepareSpeechQueue(text, checkCancelled)
}

/** 失败时保留同一续章位置以便重试，跳过纯插图章节，并拒绝目录环路。 */
internal class SpeechChapterSequence(
    chapterId: String?, nextId: String?, private val settings: ReaderSettings,
    private val load: suspend (String) -> Chapter
) {
    private val visited = mutableSetOf<String>().apply { chapterId?.let(::add) }
    private var nextId = nextId

    suspend fun next(): SpeechChapter? {
        if(!settings.speechContinueChapters) return null
        while(true) {
            currentCoroutineContext().ensureActive()
            val id = nextId ?: return null
            check(id !in visited) { "章节顺序异常，请从目录重新开始朗读" }
            val chapter = load(id)
            val jobContext = currentCoroutineContext()
            val queue = speechParagraphs(chapter, settings) { jobContext.ensureActive() }
            visited += id
            nextId = chapter.nextId
            if(queue.isNotEmpty()) return SpeechChapter(id, chapter.title, queue)
        }
    }
}
