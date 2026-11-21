package cc.novelia.app.data.library

import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookMedium
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.DEFAULT_FOLDER
import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.localFavoriteFolders

internal const val LOCAL_SERIES_TITLE_LIMIT = 120
internal const val LOCAL_SERIES_DESCRIPTION_LIMIT = 3000
internal const val LOCAL_SERIES_LIST_FIELD_LIMIT = 300

internal data class LocalSeriesEdits(
    val title: String, val authors: String, val tags: List<String>, val description: String,
    val folderId: String, val volumeKeys: List<String>, val medium: BookMedium = BookMedium.Novel,
)

internal fun localSeriesTagLength(tags: List<String>): Int = tags.sumOf { it.length } + (tags.size - 1).coerceAtLeast(0)

internal fun LibraryState.withEditedLocalSeries(ref: BookRef, creating: Boolean, edits: LocalSeriesEdits): LibraryState {
    require(ref.isLocalSeries) { "只能编辑本地总集" }
    val previous = books.firstOrNull { it.book.ref == ref }
    require(creating == (previous == null)) { if(creating) "总集已存在" else "总集已不存在" }
    val title = edits.title.trim()
    require(title.isNotEmpty() && title.length <= LOCAL_SERIES_TITLE_LIMIT && title.none(Char::isISOControl)) {
        "总集名称应为 1 至 $LOCAL_SERIES_TITLE_LIMIT 个字符"
    }
    val description = edits.description.trim()
    require(description.length <= LOCAL_SERIES_DESCRIPTION_LIMIT) { "简介不能超过 $LOCAL_SERIES_DESCRIPTION_LIMIT 个字符" }
    require(edits.authors.length <= LOCAL_SERIES_LIST_FIELD_LIMIT) { "作者不能超过 $LOCAL_SERIES_LIST_FIELD_LIMIT 个字符" }
    val tags = edits.tags.distinct()
    require(edits.tags == previous?.book?.tags || localSeriesTagLength(tags) <= LOCAL_SERIES_LIST_FIELD_LIMIT) {
        "标签总长度不能超过 $LOCAL_SERIES_LIST_FIELD_LIMIT 个字符"
    }
    val folder = localFavoriteFolders().firstOrNull { it.id == edits.folderId }?.title ?: previous?.folder ?: DEFAULT_FOLDER
    val volumeKeys = edits.volumeKeys.toSet()
    require(volumeKeys.size == edits.volumeKeys.size) { "分卷不能重复" }
    val previousKeys = previous?.let { mountedVolumes(it) }.orEmpty().map { it.book.ref.key }
    val parent = (previous ?: newSavedBook(BookCard(ref, title,
        subtitle = if (edits.medium == BookMedium.Comic) "漫画总集" else "本地总集",
        medium = edits.medium.takeIf { it == BookMedium.Comic }), folder)).let {
        it.copy(book = it.book.copy(title = title, authors = edits.authors.seriesList(), tags = tags),
            seriesDescription = description)
    }
    val updated = copy(books = if(creating) books + parent else books.map { if(it.book.ref == ref) parent else it })
    val mounted = if(previousKeys.toSet() != volumeKeys) updated.withMountedVolumes(ref.key, volumeKeys) else updated
    val moved = if(parent.folder != folder) mounted.moveShelfBooks(volumeKeys + ref.key, folder) else mounted
    return if(previousKeys != edits.volumeKeys) moved.withVolumeOrder(ref.key, edits.volumeKeys) else moved
}

private fun String.seriesList() = split('、', '，', ',', '\n').map(String::trim).filter(String::isNotEmpty).distinct()
