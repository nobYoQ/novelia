@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui.shelf

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.library.withCloudReadingMetadata
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.Page
import cc.novelia.app.data.model.WebOutline
import cc.novelia.app.ui.account.ProfileDetailList
import cc.novelia.app.ui.account.ProfileDetailCard
import cc.novelia.app.ui.account.ProfileToggle
import cc.novelia.app.ui.components.AsyncContent
import cc.novelia.app.ui.components.BookRow
import cc.novelia.app.ui.components.bookRowStatus
import cc.novelia.app.ui.components.rememberCloudBookMetadata
import cc.novelia.app.ui.account.ProfileChoiceRow
import cc.novelia.app.ui.components.ConfirmDialog
import cc.novelia.app.ui.account.ProfileEmptyState
import cc.novelia.app.ui.components.PageControls
import cc.novelia.app.ui.account.ProfileDetailScreen
import cc.novelia.app.ui.navigation.AppController

@Composable fun HistoryScreen(c: AppController) {
    val state by c.store.state.collectAsStateWithLifecycle(); val profile by c.session.profile.collectAsStateWithLifecycle()
    val history = remember(state.readingHistory) { state.readingHistory.entries.sortedByDescending { it.value.lastReadAt } }
    val booksByKey = remember(state.books) { state.books.associateBy { it.book.ref.key } }
    var tab by remember { mutableIntStateOf(0) }; var page by remember { mutableIntStateOf(0) }; var version by remember { mutableIntStateOf(0) }; var clear by remember { mutableStateOf(false) }
    fun pauseHistory(value: Boolean) { c.store.update { it.copy(historyPaused = value) }; if(profile != null) c.action { c.api.request(if(value) "PUT" else "DELETE", "user/read-history/paused") } }
    ProfileDetailScreen("阅读历史", c::back, actions = { IconButton(onClick = { clear = true }) { Icon(Icons.Outlined.DeleteSweep, "清空历史") } }) { padding -> ProfileDetailList(Modifier.padding(padding)) {
        item { ProfileChoiceRow("历史来源", listOf("此设备", "原站云端"), tab) { tab = it } }
        item { ProfileToggle("暂停阅读历史", "续读位置仍会保存", state.historyPaused, onChange = ::pauseHistory) }
        if(tab == 0) {
            if(history.isEmpty()) item { ProfileEmptyState("还没有阅读记录", "打开一本小说，最近阅读记录就会出现在这里。") }
            items(history, key = { it.key }, contentType = { "book" }) { (key, entry) ->
                val book = booksByKey[key]?.book ?: BookCard(BookRef.fromKey(key), entry.bookTitle.ifBlank { "未命名作品" })
                ProfileDetailCard { BookRow(book.copy(subtitle = entry.chapterTitle), { c.read(book.ref, entry.chapterId) }) }
            }
        } else if(profile == null) item { ProfileEmptyState("登录以查看云端历史", "原站同步到章节，本设备还会保存段落位置。", action = "登录", onAction = { c.go("login") }) }
        else item(key = "cloud-history") { AsyncContent(listOf(page, profile?.username), refreshKey = version, load = {
            val binding = c.session.capture()
            val result = c.api.get<Page<WebOutline>>("user/read-history", mapOf("page" to "$page", "pageSize" to "20"))
            c.session.ensureCurrent(binding)
            Page(result.pageNumber, result.items.map { it.card(binding.account) })
        }) { result, _ ->
            val cards = result.items
            LaunchedEffect(cards) { c.store.update { it.withCloudReadingMetadata(cards, c.session.profile.value?.username) } }
            // 云端每页最多 20 条，共用外层滚动，放大文字时仍能滚过筛选区。
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if(cards.isEmpty()) ProfileEmptyState("暂无云端阅读历史", "登录后阅读的小说会出现在这里。")
                cards.forEach { book -> key(book.ref.key) {
                    val displayed = rememberCloudBookMetadata(c, book, profile?.username, version)
                    ProfileDetailCard { BookRow(displayed, { c.book(book.ref) }, status = bookRowStatus(displayed, booksByKey[book.ref.key],
                        state.positions[book.ref.key], state.bookUpdates[book.ref.key], profile?.username, preferCloud = true)) }
                } }
                PageControls(page, result.pageNumber) { page = it }
            }
        } }
    } }
    if(clear) ConfirmDialog("清空阅读历史？", if(tab == 0) "此设备的阅读历史将被清除，续读位置会保留。" else "原站账号下的全部阅读历史将被清除。", { clear = false }, confirmLabel = "清空阅读历史") { if(tab == 0) c.store.update { it.copy(readingHistory = emptyMap(), historyMigrated = true) } else c.action { c.api.request("DELETE", "user/read-history"); version++ } }
}
