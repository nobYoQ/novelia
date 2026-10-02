package cc.novelia.app.data.catalog

import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.Page
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable

/** 字数上下限均包含边界；缺失字数始终保持未知，不能按零字处理。 */
@Serializable data class CharacterCountFilter(
    val minimum: Long? = null, val maximum: Long? = null, val includeUnknown: Boolean = false,
) {
    val active get() = minimum != null || maximum != null
    fun normalized(): CharacterCountFilter {
        val min = minimum?.coerceAtLeast(0)
        return copy(minimum = min, maximum = maximum?.coerceAtLeast(min ?: 0))
    }
    fun matches(characters: Long?): Boolean = !active || (characters?.takeIf { it >= 0 }?.let {
        (minimum == null || it >= minimum) && (maximum == null || it <= maximum)
    } ?: includeUnknown)
    fun summary(): String = when {
        !active -> "不限字数"
        minimum == null -> "不超过 ${formatCharacters(maximum!!)}"
        maximum == null -> "至少 ${formatCharacters(minimum)}"
        else -> "${formatCharacters(minimum)}～${formatCharacters(maximum)}"
    } + if(active && includeUnknown) "（含未知）" else ""
}

fun formatCharacters(value: Long): String = if(value >= 10_000 && value % 10_000 == 0L) "${value / 10_000} 万字" else "$value 字"

@Serializable data class NovelLocalFilter(
    val characters: CharacterCountFilter = CharacterCountFilter(),
    // 仅兼容旧搜索方案；SavedSearchPreset.normalized 将其展开为可见的原文标签并清空。
    val includedGroups: List<String> = emptyList(), val excludedGroups: List<String> = emptyList(),
) {
    val active get() = characters.active
    fun normalized() = NovelLocalFilter(characters.normalized())
    fun summaries(): List<String> = if(characters.active) listOf(characters.summary()) else emptyList()

    internal fun legacyTagExpression(): String {
        val included = keywordCategorySeeds.filter { it.legacyId in includedGroups && it.legacyId !in excludedGroups }.flatMap { it.originals }
        val excluded = keywordCategorySeeds.filter { it.legacyId in excludedGroups }.flatMap { it.originals }
        return SearchExpression.build("", "", "", "", included.joinToString(" "), excluded.joinToString(" "), "", "")
    }
}

data class FilteredNovelBatch(
    val books: List<BookCard> = emptyList(), val nextPage: Int = 0, val endReached: Boolean = false,
    val scanned: Int = 0, val unknown: Int = 0,
)

/** 每轮最多读三页；保留最后一页的全部命中，不能为凑整页丢掉余下作品。 */
suspend fun loadFilteredNovels(
    startPage: Int, filter: NovelLocalFilter, targetSize: Int = 20, maxPages: Int = 3,
    loadPage: suspend (Int) -> Page<BookCard>, enrich: suspend (List<BookCard>) -> List<BookCard>,
    visible: (BookCard) -> Boolean = { true },
    onProgress: suspend (FilteredNovelBatch) -> Unit = {},
): FilteredNovelBatch {
    require(startPage >= 0 && targetSize > 0 && maxPages > 0)
    var next = startPage
    var scanned = 0
    var unknown = 0
    var ended = false
    val result = linkedMapOf<String, BookCard>()
    val seen = mutableSetOf<String>()
    repeat(maxPages) {
        currentCoroutineContext().ensureActive()
        val page = loadPage(next)
        next++
        scanned += page.items.size
        val candidates = page.items.filter { seen.add(it.ref.key) && visible(it) }
        val books = enrich(candidates)
        currentCoroutineContext().ensureActive()
        books.filter(visible).forEach { book ->
            if(filter.characters.active && book.totalCharacters == null) unknown++
            if(filter.characters.matches(book.totalCharacters)) result[book.ref.key] = book
        }
        ended = next >= page.pageNumber
        // 每完成一页即发布并提交游标，后续页缓慢或失败不阻挡已获得的结果。
        onProgress(FilteredNovelBatch(result.values.toList(), next, ended, scanned, unknown))
        if(ended || result.size >= targetSize) return FilteredNovelBatch(result.values.toList(), next, ended, scanned, unknown)
    }
    return FilteredNovelBatch(result.values.toList(), next, ended, scanned, unknown)
}
