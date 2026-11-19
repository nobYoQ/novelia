package cc.novelia.app.data.model

import java.text.Collator
import java.util.Locale
import kotlinx.serialization.Serializable

@Serializable enum class FavoriteFolderSort { Original, Name, Manual }

/** 排序只用于展示。手动顺序按稳定 ID 保存，改名时仍指向同一个收藏夹。 */
@Serializable data class FavoriteFolderOrder(
    val sort: FavoriteFolderSort = FavoriteFolderSort.Original,
    val manualIds: List<String> = emptyList(),
) {
    internal fun ordered(folders: List<Folder>, defaultId: String): List<Folder> {
        val (fixed, movable) = folders.partition { it.id == defaultId }
        val sorted = when(sort) {
            FavoriteFolderSort.Original -> movable
            FavoriteFolderSort.Name -> {
                val collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE).apply { strength = Collator.PRIMARY }
                movable.sortedWith { left, right -> collator.compare(left.title, right.title) }
            }
            FavoriteFolderSort.Manual -> {
                val ranks = manualIds.withIndex().associate { (index, id) -> id to index }
                movable.sortedBy { ranks[it.id] ?: Int.MAX_VALUE }
            }
        }
        return fixed + sorted
    }
}

@Serializable data class CloudFavoriteFolderOrders(
    val web: FavoriteFolderOrder = FavoriteFolderOrder(),
    val wenku: FavoriteFolderOrder = FavoriteFolderOrder(),
) {
    internal fun forKind(isWenku: Boolean) = if(isWenku) wenku else web
}

internal fun LibraryState.localFavoriteFolders(): List<Folder> = folders.map { name ->
    Folder(folderIds[name] ?: legacyFolderId(name), name)
}

internal fun LibraryState.orderedLocalFavoriteFolders(): List<String> =
    localFavoriteFolderOrder.ordered(localFavoriteFolders(), DEFAULT_FOLDER_ID).map(Folder::title)

internal const val CLOUD_DEFAULT_FOLDER_ID = "default"

internal fun LibraryState.cloudFavoriteFolderOrder(account: String, isWenku: Boolean): FavoriteFolderOrder =
    cloudFavoriteFolderOrders[account]?.forKind(isWenku) ?: FavoriteFolderOrder()

internal fun LibraryState.withCloudFavoriteFolderOrder(account: String, isWenku: Boolean, order: FavoriteFolderOrder): LibraryState {
    val previous = cloudFavoriteFolderOrders[account] ?: CloudFavoriteFolderOrders()
    if(previous.forKind(isWenku) == order) return this
    val updated = if(isWenku) previous.copy(wenku = order) else previous.copy(web = order)
    return copy(cloudFavoriteFolderOrders = if(updated == CloudFavoriteFolderOrders()) cloudFavoriteFolderOrders - account
        else cloudFavoriteFolderOrders + (account to updated))
}
