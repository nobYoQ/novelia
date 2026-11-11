package cc.novelia.app.data.network

import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.CloudFolders
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class CloudFavoriteFolderImport(val books: List<BookCard>, val folders: CloudFolders)

/** 将收藏夹 ID 对应到当前名称；摘要缺少归属时逐页查证，未确认的书目不能猜测分组。 */
suspend fun NoveliaApi.resolveCloudFavoriteFolders(
    books: List<BookCard>,
    validate: () -> Unit = {},
    onProgress: (Int) -> Unit = {},
): CloudFavoriteFolderImport {
    val binding = session?.capture()
    val context = currentCoroutineContext()
    fun ensureCurrent() {
        context.ensureActive()
        binding?.let { session?.ensureCurrent(it) }
        validate()
    }
    require(books.none { it.ref.isLocal }) { "本地文件没有云端收藏夹" }
    ensureCurrent()
    val folders = get<CloudFolders>("user/favored")
    ensureCurrent()
    val choices = mapOf(false to folders.favoredWeb, true to folders.favoredWenku).mapValues { (_, list) ->
        list.filter { it.id.isNotBlank() && it.id != ALL_CLOUD_FAVORITES }
    }
    val folderIds = choices.mapValues { (_, list) -> list.map { it.id }.toSet() }
    val originals = books.distinctBy { it.ref }.associateBy { it.ref.key }
    val resolved = originals.filterValues { book -> book.favored in folderIds.getValue(book.ref.isWenku) }.toMutableMap()
    val unresolved = (originals.keys - resolved.keys).toMutableSet()
    onProgress(resolved.size)
    for((wenku, list) in choices) {
        val remaining = unresolved.filterTo(mutableSetOf()) { originals.getValue(it).ref.isWenku == wenku }
        for(folder in list) {
            if(remaining.isEmpty()) break
            var page = 0
            do {
                ensureCurrent()
                val result = cloudFavorites(wenku, folder.id, page, sort = "create")
                ensureCurrent()
                result.items.forEach { book ->
                    if(remaining.remove(book.ref.key)) {
                        resolved[book.ref.key] = originals.getValue(book.ref.key).copy(favored = folder.id)
                        unresolved.remove(book.ref.key)
                    }
                }
                onProgress(resolved.size)
                page++
            } while(page < result.pageNumber && remaining.isNotEmpty())
        }
    }
    ensureCurrent()
    check(unresolved.isEmpty()) { "无法确认 ${unresolved.size} 本书的云端收藏夹，请刷新云端收藏后重试" }
    return CloudFavoriteFolderImport(originals.keys.map(resolved::getValue), folders)
}
