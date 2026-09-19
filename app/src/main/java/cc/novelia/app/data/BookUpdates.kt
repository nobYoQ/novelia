package cc.novelia.app.data

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
    fun latestTranslationAt(engines: List<String>): Long = translations.filter { it.key in engines && it.value > 0 }
        .keys.maxOfOrNull { translationUpdatedAt[it] ?: checkedAt } ?: 0L
}

fun translationEngineName(engine: String) = when(engine) { "sakura" -> "Sakura"; "gpt" -> "GPT"; "youdao" -> "有道"; else -> engine }
fun BookCard.updateSnapshot(checkedAt: Long = 0) = BookUpdateSnapshot(total, translations, volumeIds, checkedAt)

/** Legacy cards lack per-engine counts: establish a baseline rather than invent historical deltas. */
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
    (translations.keys + newer.translations.keys).associateWith { engine ->
        maxOf(if((translations[engine] ?: 0) > 0) translationUpdatedAt[engine] ?: checkedAt else 0L,
            if((newer.translations[engine] ?: 0) > 0) newer.translationUpdatedAt[engine] ?: newer.checkedAt else 0L)
    }
)
