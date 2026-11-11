package cc.novelia.app.data.network

import cc.novelia.app.data.catalog.providers
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.Folder
import cc.novelia.app.data.model.Page
import cc.novelia.app.data.model.WebOutline
import cc.novelia.app.data.model.WenkuOutline
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

const val ALL_CLOUD_FAVORITES = "all"

fun cloudFolderChoices(folders: List<Folder>): List<Folder> =
    if (folders.size > 1) listOf(Folder(ALL_CLOUD_FAVORITES, "全部收藏")) + folders else folders

data class CloudWebFilter(
    val query: String = "",
    val source: String = providers.keys.joinToString(","),
    val type: Int = 0,
    val level: Int = 0,
    val translate: Int = 0,
)

/** 保留空书源选择，原站对此返回无匹配结果。 */
suspend fun NoveliaApi.cloudFavorites(
    wenku: Boolean,
    folderId: String,
    page: Int,
    sort: String = "update",
    filter: CloudWebFilter = CloudWebFilter(),
): Page<BookCard> {
    val binding = session?.capture()
    val params = buildMap {
        put("page", "$page")
        put("pageSize", "20")
        put("sort", sort)
        if (!wenku) {
            put("query", filter.query.trim())
            put("provider", filter.source)
            put("type", "${filter.type}")
            put("level", "${filter.level}")
            put("translate", "${filter.translate}")
        }
    }
    return if (wenku) get<Page<WenkuOutline>>("user/favored-wenku/${encodeSegment(folderId)}", params)
        .let { Page(it.pageNumber, it.items.map(WenkuOutline::card)) }
    else get<Page<WebOutline>>("user/favored-web/${encodeSegment(folderId)}", params)
        .let { result ->
            binding?.let { session?.ensureCurrent(it) }
            Page(result.pageNumber, result.items.map { it.card(binding?.account) })
        }
}

data class CloudFavoriteImportProgress(val pagesRead: Int, val pageCount: Int, val booksRead: Int)

/** 显式读取某一类型的全部收藏，不沿用页面的收藏夹或筛选；完整读取后才交给调用方写入。 */
suspend fun NoveliaApi.allCloudFavorites(
    wenku: Boolean,
    validate: () -> Unit = {},
    onProgress: (CloudFavoriteImportProgress) -> Unit = {},
): List<BookCard> {
    val binding = session?.capture()
    val context = currentCoroutineContext()
    fun ensureCurrent() {
        context.ensureActive()
        binding?.let { session?.ensureCurrent(it) }
        validate()
    }
    val books = linkedMapOf<String, BookCard>()
    var page = 0
    do {
        ensureCurrent()
        // 按收藏时间读取，避免作品更新引发排序变化；all 覆盖所有云端收藏夹。
        val result = cloudFavorites(wenku, ALL_CLOUD_FAVORITES, page, sort = "create")
        ensureCurrent()
        result.items.forEach { books.putIfAbsent(it.ref.key, it) }
        page++
        onProgress(CloudFavoriteImportProgress(page, result.pageNumber.coerceAtLeast(page), books.size))
    } while(page < result.pageNumber)
    ensureCurrent()
    return books.values.toList()
}
