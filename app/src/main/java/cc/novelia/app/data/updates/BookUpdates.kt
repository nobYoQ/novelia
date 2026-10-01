package cc.novelia.app.data.updates

import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.ReaderSettings
import kotlinx.serialization.Serializable

/** 最近一轮检查的完整数量基线；checkedAt 为客户端检查时间，文库以文件 ID 列表识别增量。 */
@Serializable data class BookUpdateSnapshot(
    val total: Int = 0, val translations: Map<String, Int> = emptyMap(),
    val volumeIds: List<String> = emptyList(), val checkedAt: Long = 0
)

/**
 * 尚未确认的更新数量与译文刷新时间；数量用于提示，时间用于判断已有章节缓存是否过旧。
 * 两者生命周期不同，清除已读提示后仍可保留 translationUpdatedAt。
 */
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
    // 确认未读提示后，仍保留用于缓存刷新的译文更新时间。
    fun latestTranslationAt(engines: List<String>): Long = engines.maxOfOrNull {
        translationUpdatedAt[it] ?: if((translations[it] ?: 0) > 0) checkedAt else 0L
    } ?: 0L

    /** 清除已读章节增量，只确认不晚于阅读时间的译文更新，保留之后发现的译文变化。 */
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
    val newChapters = if(wenku || (previous.total == 0 && previous.checkedAt == 0L)) 0
        else (current.total - previous.total).coerceAtLeast(0)
    return BookUpdateInfo(current.checkedAt, newChapters, translations, newVolumes,
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
