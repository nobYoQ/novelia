@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui.shelf

import cc.novelia.app.ui.components.base.AppIconButton
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.auth.SessionBinding
import cc.novelia.app.data.library.resumeDestination
import cc.novelia.app.data.library.withCloudReadingMetadata
import cc.novelia.app.data.library.withoutReadingHistory
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.Page
import cc.novelia.app.data.model.TocItem
import cc.novelia.app.data.model.WebDetail
import cc.novelia.app.data.model.WebOutline
import cc.novelia.app.data.model.withKnownChapter
import cc.novelia.app.data.model.withKnownUpdateTime
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.storage.hashName
import cc.novelia.app.ui.account.ProfileDetailList
import cc.novelia.app.ui.account.ProfileToggle
import cc.novelia.app.ui.components.base.CachedAsyncContent
import cc.novelia.app.ui.components.base.AppTextButton
import cc.novelia.app.ui.components.base.rememberCachedListState
import cc.novelia.app.ui.components.book.rememberCloudBookMetadata
import cc.novelia.app.ui.account.ProfileChoiceRow
import cc.novelia.app.ui.components.base.ConfirmDialog
import cc.novelia.app.ui.account.ProfileEmptyState
import cc.novelia.app.ui.components.base.PageControls
import cc.novelia.app.ui.account.ProfileDetailScreen
import cc.novelia.app.ui.navigation.AppController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class HistoryRemoval(val book: BookCard, val binding: SessionBinding? = null)

// 此设备历史只读取已导入的目录或当前账号的详情缓存，离线也能比较章节。
@Composable
private fun localHistoryBook(c: AppController, book: BookCard, binding: SessionBinding, generation: Long): BookCard {
    if (book.ref.isWenku) return book
    return key(book, binding, generation) {
        produceState(book) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    if (book.ref.isLocal) {
                        val chapters = c.store.documentIndex(book.ref.id).chapters
                        val last = chapters.lastOrNull() ?: return@runCatching book
                        book.copy(latestChapter = TocItem(chapterId = last.id,
                            titleZh = last.title.ifBlank { "第 ${chapters.size} 章" }))
                    } else {
                        val path = "novel/${book.ref.key}"
                        val raw = c.metadataCache.read(hashName("${binding.cacheAccount}:$path"), newerThan = c.api.detailMutationAt(path))
                            ?: return@runCatching book
                        val detail = appJson.decodeFromString<WebDetail>(raw)
                        book.copy(
                            updateAt = listOfNotNull(book.updateAt, detail.lastUpdatedAt).maxOrNull(),
                            latestChapter = detail.latestChapterFor(book),
                        )
                    }
                }.getOrDefault(book)
            }
        }.value
    }
}

@Composable fun HistoryScreen(c: AppController) {
    val state by c.store.state.collectAsStateWithLifecycle(); val profile by c.session.profile.collectAsStateWithLifecycle()
    val history = remember(state.readingHistory) { state.readingHistory.entries.sortedByDescending { it.value.lastReadAt } }
    val booksByKey = remember(state.books) { state.books.associateBy { it.book.ref.key } }
    var tab by rememberSaveable { mutableIntStateOf(0) }; var page by rememberSaveable { mutableIntStateOf(0) }; var version by remember { mutableIntStateOf(0) }; var clear by remember { mutableStateOf(false) }
    var removal by remember { mutableStateOf<HistoryRemoval?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val cacheGeneration by c.store.cacheGeneration.collectAsStateWithLifecycle()
    val binding = c.session.capture()
    val scroll = rememberCachedListState(c.uiResults.history, listOf("history-scroll", tab, if(tab == 0) 0 else page, c.session.capture(), cacheGeneration))
    fun pauseHistory(value: Boolean) { c.store.update { it.copy(historyPaused = value) }; if(profile != null) c.action { c.api.request(if(value) "PUT" else "DELETE", "user/read-history/paused") } }
    ProfileDetailScreen("阅读历史", c::back, actions = { AppIconButton(onClick = { clear = true }, enabled = !deleting && (tab == 0 || profile != null)) { Icon(Icons.Outlined.DeleteSweep, "清空历史") } }) { padding -> ProfileDetailList(Modifier.padding(padding), state = scroll) {
        item { ProfileChoiceRow("历史来源", listOf("此设备", "原站云端"), tab) { tab = it } }
        item { ProfileToggle("暂停阅读历史", "时长统计同时暂停，续读位置仍会保存", state.historyPaused, onChange = ::pauseHistory) }
        if(tab == 0) {
            if(history.isEmpty()) item { ProfileEmptyState("还没有阅读记录", "打开一本小说，最近阅读记录就会出现在这里。") }
            items(history, key = { it.key }, contentType = { "book" }) { (key, entry) ->
                val book = booksByKey[key]?.book ?: BookCard(BookRef.fromKey(key), entry.bookTitle.ifBlank { "未命名作品" })
                val position = state.positions[key]?.takeIf { it.chapterId == entry.chapterId }
                val chapter = entry.chapterTitle.takeIf(String::isNotBlank)
                    ?: position?.title?.takeIf(String::isNotBlank)
                    ?: position?.chapterIndex?.takeIf { it >= 0 }?.let { "第 ${it + 1} 章" }
                HistoryBookRow(
                    book = localHistoryBook(c, book, binding, cacheGeneration),
                    readChapterId = entry.chapterId, chapterTitle = chapter, lastReadSeconds = entry.lastReadAt / 1000L,
                    onRead = { c.read(book.ref, entry.chapterId) }, onCover = { c.book(book.ref) },
                    onRemove = { removal = HistoryRemoval(book) }, canRemove = !deleting,
                )
            }
        } else if(profile == null) item { ProfileEmptyState("登录以查看云端历史", "原站同步到章节，本设备还会保存段落位置。", action = "登录", onAction = { c.go("login") }) }
        else item(key = "cloud-history") { CachedAsyncContent(c.uiResults.history, listOf(page, binding, profile?.role, cacheGeneration), refreshKey = version,
            validate = { c.session.ensureCurrent(binding) }, load = {
            val result = c.api.get<Page<WebOutline>>("user/read-history", mapOf("page" to "$page", "pageSize" to "20"))
            c.session.ensureCurrent(binding)
            Page(result.pageNumber, result.items.map { it.card(binding.account) })
        }) { result, refresh ->
            val cards = result.items
            LaunchedEffect(page, result.pageNumber) { page = page.coerceAtMost((result.pageNumber - 1).coerceAtLeast(0)) }
            LaunchedEffect(cards) { c.store.update { it.withCloudReadingMetadata(cards, c.session.profile.value?.username) } }
            // 云端每页最多 20 条，共用外层滚动，放大文字时仍能滚过筛选区。
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if(cards.isEmpty()) ProfileEmptyState("暂无云端阅读历史", "登录后阅读的小说会出现在这里。")
                cards.forEach { book -> key(book.ref.key) {
                    val saved = booksByKey[book.ref.key]?.book
                    val displayed = rememberCloudBookMetadata(c, book, binding.account, version).withKnownUpdateTime(saved)
                    val savedProgress = saved?.cloudReading?.takeIf { it.account == binding.account }
                    val progress = displayed.cloudReading?.takeIf { it.account == binding.account }
                        ?.withKnownChapter(savedProgress) ?: savedProgress
                    var opening by remember(book.ref, binding) { mutableStateOf(false) }
                    HistoryBookRow(
                        book = displayed,
                        readChapterId = progress?.chapterId,
                        chapterTitle = progress?.chapterTitle?.takeIf(String::isNotBlank)
                            ?: progress?.chapterIndex?.takeIf { it >= 0 }?.let { "第 ${it + 1} 章" },
                        lastReadSeconds = book.cloudReading?.takeIf { it.account == binding.account }?.lastReadAt ?: progress?.lastReadAt,
                        onCover = { c.book(book.ref) },
                        onRead = {
                            if(!opening) {
                                opening = true
                                c.action {
                                    try {
                                        c.session.ensureCurrent(binding)
                                        val detail = c.detail<WebDetail>("novel/${book.ref.key}")
                                        c.session.ensureCurrent(binding)
                                        val destination = resumeDestination(detail.toc, localId = null, cloudId = detail.lastReadChapterId)
                                            ?: error("这本小说没有可阅读的章节")
                                        c.read(book.ref, destination.chapterId)
                                    } finally { opening = false }
                                }
                            }
                        },
                        onRemove = { removal = HistoryRemoval(book, binding) }, canRemove = !deleting,
                    )
                } }
                PageControls(page, result.pageNumber) { page = it }
                AppTextButton(onClick = refresh) { Text("刷新历史") }
            }
        } }
    } }
    removal?.let { target ->
        ConfirmDialog("删除阅读历史？", if(target.binding == null)
            "删除《${target.book.title}》在此设备的阅读历史，续读位置会保留。"
            else "删除《${target.book.title}》在原站云端的阅读历史，此设备的阅读历史和续读位置会保留。",
            { removal = null }, confirmLabel = "删除阅读历史") {
            if(target.binding == null) c.store.update { it.withoutReadingHistory(target.book.ref) }
            else {
                deleting = true
                c.action {
                    try {
                        c.session.ensureCurrent(target.binding)
                        // 与阅读进度共用队列，以删除意图替换同书尚未上传的进度。
                        val queued = c.cloudMutation("DELETE", "user/read-history/${target.book.ref.key}")
                        c.session.ensureCurrent(target.binding)
                        version++
                        if(!queued) c.message("云端阅读历史已删除")
                    } finally { deleting = false }
                }
            }
        }
    }
    if(clear) ConfirmDialog("清空阅读历史？", if(tab == 0) "此设备的阅读历史将被清除，续读位置会保留。" else "原站账号下的全部阅读历史将被清除。", { clear = false }, confirmLabel = "清空阅读历史") { if(tab == 0) c.store.update { it.copy(readingHistory = emptyMap(), historyMigrated = true) } else c.action { c.api.request("DELETE", "user/read-history"); version++ } }
}
