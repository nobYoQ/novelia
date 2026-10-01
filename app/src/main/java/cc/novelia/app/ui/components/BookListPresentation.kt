package cc.novelia.app.ui.components

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.Position
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.data.library.acknowledgeCompletedBookUpdates
import cc.novelia.app.data.storage.localReadingProgressCandidates
import cc.novelia.app.data.storage.restoreLocalReadingProgress
import cc.novelia.app.data.updates.BookUpdateInfo
import cc.novelia.app.ui.navigation.AppController
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.floor
import cc.novelia.app.data.model.withKnownChapter

/** progress 为 0 表示零进度，null 表示无法确定数值；已有历史但未解析章号时仍保留文字提示。 */
internal data class BookRowStatus(
    val progress: Float? = 0f,
    val progressLabel: String = "未读",
    val updateLabel: String? = null,
)

/**
 * 书架按已到达的章节计算进度，与云端记录使用同一口径；章内锚点仅用于恢复阅读。
 * 云端只能提供到达的章节，按账号隔离后再与本地记录比较，preferCloud 可要求优先展示。
 * 云端时间为秒，本地 Position.updatedAt 为毫秒；比较时统一单位，未知云端进度不视为未读。
 */
internal fun bookRowStatus(book: BookCard, saved: SavedBook?, position: Position?, update: BookUpdateInfo?, account: String? = null, preferCloud: Boolean = false): BookRowStatus {
    val savedCloud = saved?.book?.cloudReading?.takeIf { it.account == account }
    val cloudRecord = book.cloudReading?.takeIf { it.account == account }?.withKnownChapter(savedCloud) ?: savedCloud
    val cloud = cloudRecord?.takeIf { it.hasHistory }
    val unknownCloud = account != null && !book.ref.isLocal && !book.ref.isWenku && cloudRecord?.chapterResolved != true && cloud == null && position == null
    val useCloud = cloud != null && (preferCloud || position == null || (cloud.lastReadAt ?: 0) > position.updatedAt / 1000 ||
        (cloud.lastReadAt == null && (cloud.chapterIndex ?: -1) > (position.chapterIndex ?: -1)))
    val count = maxOf(book.total, saved?.book?.total ?: 0, position?.chapterCount ?: 0)
    val chapter = position?.chapterIndex
    val newChapters = (update?.newChapters ?: 0).coerceAtLeast(0)
    val finished = saved?.status == "读完" && !(preferCloud && useCloud) && newChapters == 0 &&
        (position?.chapterCount == null || position.chapterCount >= count)
    // 云端记录到达的章节，文字与进度填充均采用同一从 1 开始的章号。
    val cloudChapter = cloud?.chapterIndex?.takeIf { it >= 0 && it < (cloud.chapterCount ?: 0) }?.plus(1)
    val progress = when {
        finished -> 1f
        useCloud -> cloudChapter?.let { it.toFloat() / maxOf(count, cloud?.chapterCount ?: 0) }
        saved?.status == "读完" && position == null && newChapters > 0 && count > 0 -> (count - newChapters).coerceAtLeast(0).toFloat() / count
        unknownCloud -> null
        position == null -> 0f
        chapter == null || chapter < 0 || count <= 0 || chapter >= count -> null
        else -> ((chapter + 1).toFloat() / count).coerceIn(0f, 1f)
    }
    val label = when {
        finished -> "已读 100%"
        useCloud -> cloudChapter?.let { "读到第 $it 章" } ?: "有阅读记录"
        unknownCloud && progress == null -> "云端进度待同步"
        position == null && progress == 0f -> "未读"
        progress == null -> "继续阅读"
        else -> "已读 ${floor(progress * 100).toInt().coerceIn(0, 100)}%"
    }
    val updates = when {
        (update?.newChapters ?: 0) > 0 -> "更新 ${update!!.newChapters} 章"
        (update?.newVolumes ?: 0) > 0 -> "更新 ${update!!.newVolumes} 卷"
        update?.translations?.values?.any { it > 0 } == true -> "译文更新"
        saved?.hasUpdates == true || update?.hasChanges == true -> "有更新"
        else -> null
    }
    return BookRowStatus(progress, label, updates)
}

internal fun bookUpdateDate(epochSeconds: Long?, zone: ZoneId = ZoneId.systemDefault()): String? =
    formatBookUpdateTime(epochSeconds, "yyyy-MM-dd", zone)

internal fun bookUpdateDateTime(epochSeconds: Long?, zone: ZoneId = ZoneId.systemDefault()): String? =
    formatBookUpdateTime(epochSeconds, "yyyy-MM-dd HH:mm", zone)

private fun formatBookUpdateTime(epochSeconds: Long?, pattern: String, zone: ZoneId): String? =
    epochSeconds?.takeIf { it > 0 }?.let { value ->
        runCatching { DateTimeFormatter.ofPattern(pattern).format(Instant.ofEpochSecond(value).atZone(zone)) }.getOrNull()
    }

internal data class BookListPresentation(
    val books: Map<String, SavedBook> = emptyMap(),
    val positions: Map<String, Position> = emptyMap(),
    val updates: Map<String, BookUpdateInfo> = emptyMap(),
    val account: String? = null,
)

internal val LocalBookListPresentation = compositionLocalOf { BookListPresentation() }

@Composable internal fun rememberBookListPresentation(c: AppController): BookListPresentation {
    val profile by c.session.profile.collectAsStateWithLifecycle()
    val source = remember(c.store) {
        c.store.state.map { BookListPresentation(it.books.associateBy { saved -> saved.book.ref.key }, it.positions, it.bookUpdates) }
            .distinctUntilChanged()
    }
    val presentation by source.collectAsStateWithLifecycle(initialValue = remember(c.store) {
        c.store.state.value.let { BookListPresentation(it.books.associateBy { saved -> saved.book.ref.key }, it.positions, it.bookUpdates) }
    })
    val legacyLocalPositions = remember(c.store, presentation.books, presentation.positions) {
        c.store.state.value.localReadingProgressCandidates()
    }
    LaunchedEffect(c.store, legacyLocalPositions) {
        c.store.restoreLocalReadingProgress(legacyLocalPositions)
    }
    LaunchedEffect(c.store, presentation.books, presentation.positions, presentation.updates, profile?.username) {
        c.store.update { it.acknowledgeCompletedBookUpdates(profile?.username) }
    }
    return remember(presentation, profile?.username) { presentation.copy(account = profile?.username) }
}
