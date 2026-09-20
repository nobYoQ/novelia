package cc.novelia.app.data.network

import cc.novelia.app.data.catalog.providers
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.Folder
import cc.novelia.app.data.model.Page
import cc.novelia.app.data.model.WebOutline
import cc.novelia.app.data.model.WenkuOutline

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

/** Empty provider selection deliberately stays empty: the site returns no matches. */
suspend fun NoveliaApi.cloudFavorites(
    wenku: Boolean,
    folderId: String,
    page: Int,
    sort: String = "update",
    filter: CloudWebFilter = CloudWebFilter(),
): Page<BookCard> {
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
        .let { Page(it.pageNumber, it.items.map(WebOutline::card)) }
}
