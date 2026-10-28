package cc.novelia.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.cache.ChapterCacheSummary
import cc.novelia.app.data.catalog.providers
import cc.novelia.app.ui.account.*
import cc.novelia.app.ui.components.base.AppTextButton
import cc.novelia.app.ui.components.base.friendlyMessage
import cc.novelia.app.ui.components.book.chapterCacheSize
import cc.novelia.app.ui.components.book.rememberChapterCacheActions
import cc.novelia.app.ui.navigation.AppController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun ChapterCacheScreen(c: AppController) {
    val revision by c.store.chapterCacheRevision.collectAsStateWithLifecycle()
    var summaries by remember { mutableStateOf<List<ChapterCacheSummary>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val actions = rememberChapterCacheActions(c)
    LaunchedEffect(c, revision, retry) {
        failure = null
        try { summaries = withContext(Dispatchers.IO) { c.store.chapterCacheSummaries() } }
        catch(error: CancellationException) { throw error }
        catch(error: Exception) { failure = error.friendlyMessage() }
    }
    ProfileDetailScreen("书籍章节缓存", c::back) { padding ->
        ProfileDetailList(Modifier.padding(padding)) {
            item {
                val entries = summaries.orEmpty()
                ProfileSummary("章节缓存", if(summaries == null) "正在统计…" else
                    "${entries.count { it.ref != null }} 本书 · ${entries.sumOf { it.chapters }} 章 · ${chapterCacheSize(entries.sumOf { it.bytes })}\n按占用空间排序，点击书籍可单独清理。阅读进度和本地小说会保留。")
            }
            failure?.let { message -> item {
                Text(message, color = MaterialTheme.colorScheme.error)
                AppTextButton(onClick = { retry++ }) { Text("重试") }
            } }
            if(summaries?.isEmpty() == true) item {
                ProfileEmptyState("暂无章节缓存", "在线阅读或手动缓存章节后，可在这里按书管理。")
            }
            items(summaries.orEmpty(), key = { it.ref?.key ?: "unidentified" }) { summary ->
                ProfileMenuRow(summary.title,
                    "${summary.chapters} 章 · ${chapterCacheSize(summary.bytes)}" +
                        (summary.ref?.let { "\n${providers[it.provider] ?: it.provider}" } ?: "\n缺少书籍归属，打开对应小说详情可尝试识别。"),
                    Icons.Outlined.AutoStories, { if(!actions.busy) actions.request(summary) }, trailing = {
                        Icon(Icons.Outlined.DeleteOutline, "清理章节缓存")
                    })
            }
            if(actions.busy) item { Text("正在清理…") }
        }
    }
}
