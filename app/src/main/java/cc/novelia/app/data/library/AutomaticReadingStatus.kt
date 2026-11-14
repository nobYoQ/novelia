package cc.novelia.app.data.library

import cc.novelia.app.data.model.*
import cc.novelia.app.data.webdav.SyncDomain
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray

/** 与旧 JSON 的缺失字段区分：只有真正新增书目才使用自动管理。 */
fun LibraryState.newSavedBook(book: BookCard, folder: String = DEFAULT_FOLDER): SavedBook = SavedBook(
    book, folder, status = if(positions.containsKey(book.ref.key) || book.cloudReading?.hasHistory == true) "在读" else "想读",
    readingStatusControl = ReadingStatusControl(true, UUID.randomUUID().toString(),
        knownChapterCount = maxOf(book.total, positions[book.ref.key]?.chapterCount ?: 0)))

fun LibraryState.withAutoReadingStatus(enabled: Boolean): LibraryState =
    if(enabled == autoReadingStatus) this else copy(autoReadingStatus = enabled, autoReadingStatusRevision = UUID.randomUUID().toString())

/** 收集已观察的管理版本，恢复自动明确取代它们；未观察的手动意图仍由同步保留。 */
private fun LibraryState.nextReadingControl(saved: SavedBook, automatic: Boolean): ReadingStatusControl {
    val prior = saved.readingStatusControl
    val observed = syncReplica.documents[SyncDomain.FAVORITES]?.records?.get(saved.book.ref.key)?.fields?.get("status")
        ?.candidates.orEmpty().filterNot { it.deleted }.flatMap { candidate -> (candidate.value as? JsonObject)?.let {
            listOfNotNull(it["revision"]?.jsonPrimitive?.contentOrNull) + it["supersedes"]?.jsonArray.orEmpty().map { value -> value.jsonPrimitive.content }
        } ?: listOf("legacy") }
    return ReadingStatusControl(automatic, UUID.randomUUID().toString(),
        prior?.supersedes.orEmpty() + (prior?.revision ?: "legacy") + observed,
        knownChapterCount = maxOf(saved.book.total, prior?.knownChapterCount ?: 0),
        completedChapterCount = prior?.completedChapterCount)
}

fun LibraryState.withManualReadingStatus(keys: Set<String>, status: String): LibraryState {
    require(status in readingStatuses) { "不支持的阅读状态" }
    return copy(books = books.map { if(it.book.ref.key in keys) it.copy(status = status,
        readingStatusControl = nextReadingControl(it, false)) else it })
}

fun LibraryState.withAutomaticReadingStatus(keys: Set<String>): LibraryState = copy(books = books.map { saved ->
    if(saved.book.ref.key !in keys || saved.readingStatusControl?.automatic == true) saved else {
        val control = nextReadingControl(saved, true)
        val position = positions[saved.book.ref.key]
        val total = maxOf(saved.book.total, updateSnapshots[saved.book.ref.key]?.total ?: 0, position?.chapterCount ?: 0, control.knownChapterCount)
        val status = when {
            !saved.book.ref.isWenku && total > 0 && (control.completedChapterCount ?: 0) >= total -> "读完"
            position != null || saved.book.cloudReading?.hasHistory == true || saved.book.ref.isWenku &&
                books.any { it.parentWenkuKey == saved.book.ref.key && it.book.ref.key in positions } -> "在读"
            else -> "想读"
        }
        saved.copy(status = status, readingStatusControl = control.copy(knownChapterCount = total))
    }
})

@Serializable data class ReadingStatusToken(
    val revision: String?, val settingsRevision: String,
    val parentKey: String? = null, val parentRevision: String? = null)

fun LibraryState.readingStatusToken(ref: BookRef): ReadingStatusToken? {
    if(!autoReadingStatus) return null
    val saved = books.firstOrNull { it.book.ref == ref } ?: return null
    val parent = books.firstOrNull { it.book.ref.key == saved.parentWenkuKey && it.book.ref.isWenku }
    return ReadingStatusToken(saved.readingStatusControl?.revision, autoReadingStatusRevision,
        parent?.book?.ref?.key, parent?.readingStatusControl?.revision)
}

/** 仅由真实正文阅读/完整章朗读调用；普通位置保存、恢复和同步均不能制造阅读事件。 */
fun LibraryState.withReadingActivity(ref: BookRef, token: ReadingStatusToken, position: Position,
    atChapterEnd: Boolean = false, completeCatalog: Boolean = false): LibraryState {
    if(!autoReadingStatus || token.settingsRevision != autoReadingStatusRevision) return this
    val saved = books.firstOrNull { it.book.ref == ref } ?: return this
    if(saved.readingStatusControl?.revision != token.revision || (position.paragraphCount ?: 0) <= 0) return this
    val control = saved.readingStatusControl
    val total = maxOf(saved.book.total, updateSnapshots[ref.key]?.total ?: 0, position.chapterCount ?: 0,
        control?.knownChapterCount ?: 0)
    val completed = !ref.isWenku && atChapterEnd && completeCatalog && total > 0 && position.chapterCount == total && position.chapterIndex == total - 1
    val next = if(control?.automatic == true) saved.copy(
        status = if(completed || saved.status == "读完" && (control.completedChapterCount ?: 0) >= total) "读完" else "在读",
        readingStatusControl = control.copy(knownChapterCount = total,
            completedChapterCount = if(completed) total else control.completedChapterCount)) else saved
    // 出版目录与上传文件不是同一个集合；仅凭本地已挂载卷不能证明整部作品读完。
    return copy(books = books.map { book -> when {
        book.book.ref == ref -> next
        book.book.ref.key == token.parentKey && saved.parentWenkuKey == token.parentKey &&
            book.readingStatusControl?.revision == token.parentRevision && book.readingStatusControl?.automatic == true && book.status == "想读" -> book.copy(status = "在读")
        else -> book
    } })
}

/** 新目录只撤销自动完成；译文变化、回看和手动完成均不降级。 */
fun LibraryState.withReadingStatusCatalog(ref: BookRef): LibraryState {
    if(!autoReadingStatus) return this
    return copy(books = books.map { saved ->
        val control = saved.readingStatusControl
        val total = maxOf(saved.book.total, updateSnapshots[ref.key]?.total ?: 0)
        if(saved.book.ref == ref && control?.automatic == true && saved.status == "读完" &&
            total > (control.completedChapterCount ?: Int.MAX_VALUE)) saved.copy(status = "在读",
                readingStatusControl = control.copy(knownChapterCount = total)) else saved
    })
}
