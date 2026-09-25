@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui.discover

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
import cc.novelia.app.data.model.Page
import cc.novelia.app.data.model.WebOutline
import cc.novelia.app.ui.components.AppLazyColumn
import cc.novelia.app.ui.components.AppScrollColumn
import cc.novelia.app.ui.components.AppSheet
import cc.novelia.app.ui.components.AsyncContent
import cc.novelia.app.ui.components.BookRow
import cc.novelia.app.ui.components.ChoiceRow
import cc.novelia.app.ui.components.EmptyState
import cc.novelia.app.ui.components.PageControls
import cc.novelia.app.ui.components.Screen
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.MotionContent

private val kakuyomuGenres = listOf("综合", "异世界幻想", "现代幻想", "科幻", "恋爱", "浪漫喜剧", "现代戏剧", "恐怖", "推理", "散文·纪实", "历史·时代·传奇", "创作论·评论", "诗·童话·其他")
private val syosetuGenres = listOf("恋爱：异世界", "恋爱：现实世界", "幻想：高幻想", "幻想：低幻想", "文学：纯文学", "文学：人性剧", "文学：历史", "文学：推理", "文学：恐怖", "文学：动作", "文学：喜剧", "科幻：VR游戏", "科幻：宇宙", "科幻：空想科学", "科幻：惊悚", "其他：童话", "其他：诗", "其他：散文", "其他：其他")
@Composable fun RankScreen(c: AppController) {
    val local by c.store.state.collectAsStateWithLifecycle()
    var source by rememberSaveable { mutableIntStateOf(0) }; var kind by rememberSaveable { mutableIntStateOf(1) }; var genre by rememberSaveable { mutableIntStateOf(0) }; var range by rememberSaveable { mutableIntStateOf(0) }; var status by rememberSaveable { mutableIntStateOf(0) }; var page by rememberSaveable { mutableIntStateOf(0) }; var filters by remember { mutableStateOf(false) }
    val provider = if(source == 0) "syosetu" else "kakuyomu"
    val ranges = if(source == 0) listOf("总计", "每年", "季度", "每月", "每周", "每日") else listOf("总计", "每年", "每月", "每周", "每日")
    val states = if(source == 0) listOf("全部", "短篇", "连载", "完结") else listOf("全部", "长篇", "短篇")
    val genres = if(source == 1) kakuyomuGenres else if(kind == 2) listOf("恋爱", "幻想", "文学/科幻/其他") else syosetuGenres
    val params = buildMap { put("range", ranges[range]); put("status", states[status]); if(source == 0) { put("type", listOf("流派", "综合", "异世界转生/转移")[kind]); put("page", "${page + 1}") }; if(source == 1 || kind != 1) put("genre", genres[genre]) }
    Screen("排行榜", c::back, actions = { IconButton(onClick = { filters = true }) { Icon(Icons.Outlined.Tune, "榜单条件") } }) { padding ->
        Column(Modifier.padding(padding)) {
            ChoiceRow("平台", listOf("成为小说家吧", "Kakuyomu"), source) { source = it; range = 0; genre = 0; status = 0; page = 0 }
            MotionContent(listOf(source, kind, genre, range, status), animateInitial = false) { Text("${params["type"] ?: genres[genre]} · ${ranges[range]} · ${states[status]}", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge) }
            AsyncContent(listOf(provider, params, local.blockedAuthors), load = {
                c.api.get<Page<WebOutline>>("novel/rank/$provider", params).let {
                    Page(it.pageNumber, enrichAuthors(it.items.map(WebOutline::card), c, local.blockedAuthors))
                }
            }) { result, _ ->
                val cards = remember(result.items, local.blockedBooks, local.blockedTags, local.blockedAuthors) {
                    result.items.filter { visibleBook(it, local) }
                }
                AppLazyColumn { if(cards.isEmpty()) item { EmptyState("这个榜单暂时没有作品", "可以切换周期或流派；榜单数据由原站获取。") }; items(cards, key = { it.ref.key }, contentType = { "book" }) { book -> BookRow(book, { c.book(book.ref) }, showReadingProgress = false) }; item { PageControls(page, result.pageNumber) { page = it } } }
            }
        }
    }
    if(filters) AppSheet(onDismissRequest = { filters = false }) { AppScrollColumn(contentModifier = Modifier.padding(bottom = 24.dp)) {
        if(source == 0) ChoiceRow("榜单", listOf("流派", "综合", "异世界转生/转移"), kind) { kind = it; genre = 0; page = 0 }
        if(source == 1 || kind != 1) ChoiceRow("流派", genres, genre) { genre = it; page = 0 }
        ChoiceRow("周期", ranges, range) { range = it; page = 0 }; ChoiceRow("状态", states, status) { status = it; page = 0 }
        Button(onClick = { filters = false }, Modifier.fillMaxWidth().padding(horizontal = 20.dp)) { Text("查看榜单") }
    } }
}
