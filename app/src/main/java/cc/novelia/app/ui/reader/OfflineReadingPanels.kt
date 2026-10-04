@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.chapters.ChapterOffline
import cc.novelia.app.data.library.chapterCacheRange
import cc.novelia.app.data.library.readingDestination
import cc.novelia.app.data.model.BookRef
import cc.novelia.app.data.model.Chapter
import cc.novelia.app.data.model.LocalDocument
import cc.novelia.app.data.model.toReaderChapter
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.data.model.TocItem
import cc.novelia.app.data.model.WebDetail
import cc.novelia.app.data.storage.appJson
import cc.novelia.app.data.storage.hashName
import cc.novelia.app.reader.*
import cc.novelia.app.ui.components.AppAlertDialog
import cc.novelia.app.ui.components.AppLazyColumn
import cc.novelia.app.ui.components.TogglePreference
import cc.novelia.app.ui.components.friendlyMessage
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.LocalEInkMode
import cc.novelia.app.ui.theme.motionClickable
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun ChapterCacheDialog(c: AppController, ref: BookRef, toc: List<TocItem>, current: String?, onChanged: () -> Unit, onDismiss: () -> Unit) {
    val count = toc.count { it.chapterId != null }
    val firstChapter = (readingDestination(toc, current)?.number ?: 1).coerceAtLeast(1)
    var first by remember { mutableStateOf(firstChapter.toString()) }
    var last by remember { mutableStateOf((firstChapter + 19).coerceAtMost(count).toString()) }
    var wifiOnly by remember { mutableStateOf(c.store.state.value.wifiOnly) }
    var job by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var completed by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun close() { job?.cancel(); onDismiss() }
    AppAlertDialog(onDismissRequest = ::close, title = { Text("选择离线缓存范围") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("共 $count 章，按目录正序编号。每批最多 200 章，已有缓存会跳过；总缓存预算 256 MB，超出时较旧内容会自动清理。")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(first, { first = it.filter(Char::isDigit).take(7) }, label = { Text("起始章") }, singleLine = true, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f).testTag("cache-first"))
                OutlinedTextField(last, { last = it.filter(Char::isDigit).take(7) }, label = { Text("结束章") }, singleLine = true, enabled = !busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f).testTag("cache-last"))
            }
            if(!busy) TogglePreference("仅 Wi-Fi 缓存", "离开 Wi-Fi 后停止后续请求", wifiOnly) { wifiOnly = it }
            // 确定进度直接跟随已完成工作量，不使用循环动画。
            if(busy) { if(!LocalEInkMode.current) LinearProgressIndicator(progress = { completed.toFloat() / total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth()); Text("正在缓存 $completed / $total 章") }
            if(message.isNotBlank()) Text(message)
        }
    }, confirmButton = {
        TextButton(enabled = !busy && count > 0, onClick = {
            val ids = runCatching { chapterCacheRange(toc, first.toIntOrNull() ?: 0, last.toIntOrNull() ?: 0) }
            if(ids.isFailure) message = ids.exceptionOrNull()?.message.orEmpty()
            else {
                busy = true; completed = 0; total = ids.getOrThrow().size; message = ""
                job = scope.launch {
                    try {
                        ChapterOffline(c.store, c.api, c.session).cache(ref, ids.getOrThrow(), wifiOnly) { done, _ -> withContext(Dispatchers.Main) { completed = done } }
                        message = "缓存完成，可在目录查看当前离线范围。"
                    } catch(e: CancellationException) { message = "已取消，已完成的缓存会保留。"; throw e }
                    catch(e: Exception) { message = e.message?.take(160) ?: "缓存暂停，请稍后重试。" }
                    finally { busy = false; onChanged() }
                }
            }
        }) { Text("开始缓存") }
    }, dismissButton = { TextButton(onClick = { if(busy) { job?.cancel(); message = "已取消，已完成的缓存会保留。" } else close() }) { Text(if(busy) "取消缓存" else "关闭") } })
}

private data class SearchScope(val toc: List<TocItem>, val document: LocalDocument?, val cachedIds: Set<String>, val completeDirectory: Boolean = true)

@Composable internal fun BookSearchPanel(c: AppController, ref: BookRef, chapterId: String, chapter: Chapter, settings: ReaderSettings, onOpen: (BookTextMatch) -> Unit) {
    var query by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf<SearchScope?>(null) }
    var result by remember { mutableStateOf<BookTextSearchResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var requestVersion by remember { mutableIntStateOf(0) }
    val tasks = rememberCoroutineScope()
    LaunchedEffect(ref) {
        try {
        scope = withContext(Dispatchers.IO) {
            if(ref.isLocal) {
                val document = c.store.documentIndex(ref.id)
                SearchScope(document.chapters.map { TocItem(it.title, it.title, it.id) }, document, document.chapters.map { it.id }.toSet())
            } else {
                val account = c.session.capture().account ?: "guest"
                // 阅读历史写入会使新鲜元数据失效；离线搜索仍可使用
                // 最近的目录快照，避免因此额外发起网络请求。
                val cachedDirectory = File(c.store.metadataDir, hashName("$account:novel/${ref.key}") + ".json")
                val savedToc = runCatching { appJson.decodeFromString<WebDetail>(cachedDirectory.readText(Charsets.UTF_8)).toc }
                    .getOrNull()?.takeIf { it.any { entry -> entry.chapterId != null } }
                val toc = savedToc ?: listOf(TocItem(chapter.titleJp, chapter.titleZh, chapterId))
                val ids = buildSet { for(item in toc) { currentCoroutineContext().ensureActive(); item.chapterId?.takeIf { c.store.chapterFile(ref, it).isFile }?.let { add(it) } } }
                SearchScope(toc, null, ids, completeDirectory = savedToc != null)
            }
        }
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) { failure = e.friendlyMessage() }
    }
    Column(Modifier.fillMaxHeight(.85f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("整本搜索", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
        val source = scope
        Text(if(source == null) (if(failure == null) "正在读取离线目录…" else "离线目录暂不可用") else if(ref.isLocal) "本地文件 · ${source.toc.size} 章" else if(!source.completeDirectory) "未找到离线目录，仅搜索当前缓存章节。" else "离线目录快照 · 已缓存 ${source.cachedIds.size} / ${source.toc.count { it.chapterId != null }} 章；未缓存章节不在搜索范围内。", style = MaterialTheme.typography.bodySmall)
        Text("按当前阅读语言和译文设置搜索，每次最多显示 200 条结果。", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it.take(200); requestVersion++; job?.cancel(); busy = false; result = null; failure = null }, label = { Text("搜索整本正文") }, singleLine = true, modifier = Modifier.weight(1f).testTag("book-search-query"))
            TextButton(onClick = {
                if(busy) { requestVersion++; job?.cancel(); busy = false }
                else if(source != null) {
                    val request = ++requestVersion
                    busy = true; failure = null; result = null
                    val term = query
                    job = tasks.launch {
                        try {
                            val found = withContext(Dispatchers.Default) {
                                searchBookText(source.toc, term, settings, load = { id ->
                                    currentCoroutineContext().ensureActive()
                                    if(ref.isLocal) withContext(Dispatchers.IO) { c.store.documentChapter(ref.id, id).toReaderChapter() }
                                    else if(id in source.cachedIds) withContext(Dispatchers.IO) { c.store.cachedChapter(ref, id) } else null
                                })
                            }
                            currentCoroutineContext().ensureActive()
                            result = found
                        } catch(e: CancellationException) { throw e }
                        catch(e: Exception) { failure = e.friendlyMessage() }
                        finally { if(request == requestVersion) busy = false }
                    }
                }
            }, enabled = busy || source != null && query.isNotBlank()) { Text(if(busy) "取消" else "搜索") }
        }
        if(busy) Text("正在搜索…")
        failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        result?.let { found ->
            Text("已搜索 ${found.scannedChapters} 章 · 找到 ${found.matches.size} 处匹配${if(found.truncated) " · 达到结果或正文上限，请缩小搜索词" else ""}", style = MaterialTheme.typography.labelMedium)
            AppLazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(found.matches, key = { "${it.chapterId}/${it.paragraph}/${it.part}/${it.start}" }) { match ->
                    ListItem(headlineContent = { Text(match.chapterLabel) }, supportingContent = { Text(match.snippet) }, modifier = Modifier.motionClickable { onOpen(match) })
                }
                if(found.matches.isEmpty()) item { Text("搜索范围内没有匹配文字。", Modifier.padding(vertical = 16.dp)) }
            }
        }
    }
}
