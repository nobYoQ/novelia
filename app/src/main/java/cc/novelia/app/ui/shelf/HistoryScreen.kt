@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui.shelf

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.library.withCloudReadingMetadata
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.Page
import cc.novelia.app.data.model.WebOutline
import cc.novelia.app.ui.components.AppLazyColumn
import cc.novelia.app.ui.components.AsyncContent
import cc.novelia.app.ui.components.BookRow
import cc.novelia.app.ui.components.bookRowStatus
import cc.novelia.app.ui.components.rememberCloudBookMetadata
import cc.novelia.app.ui.components.ChoiceRow
import cc.novelia.app.ui.components.ConfirmDialog
import cc.novelia.app.ui.components.EmptyState
import cc.novelia.app.ui.components.MenuRow
import cc.novelia.app.ui.components.PageControls
import cc.novelia.app.ui.components.Screen
import cc.novelia.app.ui.navigation.AppController

@Composable fun HistoryScreen(c: AppController) {
    val state by c.store.state.collectAsStateWithLifecycle(); val profile by c.session.profile.collectAsStateWithLifecycle()
    val history = remember(state.positions) { state.positions.entries.sortedByDescending { it.value.updatedAt } }
    val booksByKey = remember(state.books) { state.books.associateBy { it.book.ref.key } }
    var tab by remember { mutableIntStateOf(0) }; var page by remember { mutableIntStateOf(0) }; var version by remember { mutableIntStateOf(0) }; var clear by remember { mutableStateOf(false) }
    fun pauseHistory(value: Boolean) { c.store.update { it.copy(historyPaused = value) }; if(profile != null) c.action { c.api.request(if(value) "PUT" else "DELETE", "user/read-history/paused") } }
    Screen("阅读历史", c::back, actions = { IconButton(onClick = { clear = true }) { Icon(Icons.Outlined.DeleteSweep, "清空历史") } }) { padding -> Column(Modifier.padding(padding)) {
        ChoiceRow("记录位置", listOf("此设备", "原站云端"), tab) { tab = it }
        MenuRow("暂停阅读历史", "暂停后不记录新的阅读位置", Icons.Outlined.HistoryToggleOff, { pauseHistory(!state.historyPaused) }, trailing = { Switch(state.historyPaused, ::pauseHistory) })
        if(tab == 0) AppLazyColumn {
            if(history.isEmpty()) item { EmptyState("还没有阅读记录", "打开一本小说，阅读进度就会出现在这里。") }
            items(history, key = { it.key }, contentType = { "book" }) { (key, position) -> val book = booksByKey[key]?.book ?: BookCard(BookRef.fromKey(key), position.title); BookRow(book.copy(subtitle = position.title), { c.read(book.ref, position.chapterId) }) }
        } else if(profile == null) EmptyState("登录以查看云端历史", "原站同步到章节，本设备还会保存段落位置。", action = "登录", onAction = { c.go("login") })
        else AsyncContent(listOf(page, profile?.username), refreshKey = version, load = {
            val binding = c.session.capture()
            val result = c.api.get<Page<WebOutline>>("user/read-history", mapOf("page" to "$page", "pageSize" to "20"))
            c.session.ensureCurrent(binding)
            Page(result.pageNumber, result.items.map { it.card(binding.account) })
        }) { result, _ ->
            val cards = result.items
            LaunchedEffect(cards) { c.store.update { it.withCloudReadingMetadata(cards, c.session.profile.value?.username) } }
            AppLazyColumn {
                if(cards.isEmpty()) item { EmptyState("暂无云端阅读历史", "登录后阅读的小说会出现在这里。") }
                items(cards, key = { it.ref.key }, contentType = { "book" }) { book ->
                    val displayed = rememberCloudBookMetadata(c, book, profile?.username, version)
                    BookRow(displayed, { c.book(book.ref) }, status = bookRowStatus(displayed, booksByKey[book.ref.key],
                        state.positions[book.ref.key], state.bookUpdates[book.ref.key], profile?.username, preferCloud = true))
                }
                item { PageControls(page, result.pageNumber) { page = it } }
            }
        }
    } }
    if(clear) ConfirmDialog("清空阅读历史？", if(tab == 0) "此设备保存的阅读位置将被清除。" else "原站账号下的全部阅读历史将被清除。", { clear = false }) { if(tab == 0) c.store.update { it.copy(positions = emptyMap()) } else c.action { c.api.request("DELETE", "user/read-history"); version++ } }
}
