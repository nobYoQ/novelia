package cc.novelia.app.data.updates

import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.ReaderSettings
import kotlinx.serialization.Serializable

@Serializable data class BookUpdateSnapshot(
    val total: Int = 0, val translations: Map<String, Int> = emptyMap(),
    val volumeIds: List<String> = emptyList(), val checkedAt: Long = 0
)

@Serializable data class BookUpdateInfo(
    val checkedAt: Long = 0, val newChapters: Int = 0,
    val translations: Map<String, Int> = emptyMap(), val newVolumes: Int = 0,
    val translationUpdatedAt: Map<String, Long> = emptyMap()
) {
    val hasChanges get() = newChapters > 0 || newVolumes > 0 || translations.values.any { it > 0 }
    val summary: String get() = buildList {
        if(newChapters > 0) add("新增 $newChapters 章")
        if(newVolumes > 0) add("新增 $newVolumes 个分卷文件")
        listOf("sakura", "gpt", "youdao").forEach { engine ->
            translations[engine]?.takeIf { it > 0 }?.let { add("${translationEngineName(engine)} 补齐 $it 章") }
        }
    }.joinToString(" · ")
    fun relevantTo(settings: ReaderSettings) = newChapters > 0 || newVolumes > 0 ||
        (settings.mode != "jp" && (translations[settings.engines.firstOrNull()] ?: 0) > 0)
    // Cache freshness survives acknowledging the unread badge.
    fun latestTranslationAt(engines: List<String>): Long = engines.maxOfOrNull {
        translationUpdatedAt[it] ?: if((translations[it] ?: 0) > 0) checkedAt else 0L
    } ?: 0L

    fun acknowledgeThrough(readAt: Long): BookUpdateInfo {
        val freshness = (translationUpdatedAt.keys + translations.filterValues { it > 0 }.keys)
            .associateWith { translationUpdatedAt[it] ?: checkedAt }.filterValues { it > 0 }
        return copy(newChapters = 0,
            translations = translations.filter { (engine, count) -> count > 0 && (freshness[engine] ?: 0L) > readAt },
            translationUpdatedAt = freshness)
    }
}

fun translationEngineName(engine: String) = when(engine) { "sakura" -> "Sakura"; "gpt" -> "GPT"; "youdao" -> "有道"; else -> engine }
fun BookCard.updateSnapshot(checkedAt: Long = 0) = BookUpdateSnapshot(total, translations, volumeIds, checkedAt)

/**
 * 计算相邻检查快照的正向增量，数量减少不当作新内容。缺少某引擎旧计数时只建立基线，
 * 防止升级后的第一次检查把所有历史译文都报为新增；文库优先按文件 ID 集合判断新分卷。
 * checkedAt 表示客户端发现更新的时间，用于后续判断章节缓存是否可能缺少新增译文。
 */
fun detectBookUpdate(previous: BookUpdateSnapshot, current: BookUpdateSnapshot, wenku: Boolean): BookUpdateInfo {
    val translations = current.translations.mapNotNull { (engine, count) ->
        previous.translations[engine]?.let { old -> (count - old).coerceAtLeast(0).takeIf { it > 0 }?.let { engine to it } }
    }.toMap()
    val newVolumes = if(!wenku || (previous.checkedAt == 0L && previous.volumeIds.isEmpty())) 0 else if(previous.volumeIds.isNotEmpty())
        (current.volumeIds.toSet() - previous.volumeIds.toSet()).size
    else (current.total - previous.total).coerceAtLeast(0)
    return BookUpdateInfo(current.checkedAt, if(wenku) 0 else (current.total - previous.total).coerceAtLeast(0), translations, newVolumes,
        translations.keys.associateWith { current.checkedAt })
}

fun BookUpdateInfo.accumulate(newer: BookUpdateInfo) = BookUpdateInfo(
    maxOf(checkedAt, newer.checkedAt), newChapters + newer.newChapters,
    (translations.keys + newer.translations.keys).associateWith { translations.getOrDefault(it, 0) + newer.translations.getOrDefault(it, 0) },
    newVolumes + newer.newVolumes,
    (translationUpdatedAt.keys + newer.translationUpdatedAt.keys + translations.keys + newer.translations.keys).associateWith { engine ->
        maxOf(translationUpdatedAt[engine] ?: if((translations[engine] ?: 0) > 0) checkedAt else 0L,
            newer.translationUpdatedAt[engine] ?: if((newer.translations[engine] ?: 0) > 0) newer.checkedAt else 0L)
    }
)
