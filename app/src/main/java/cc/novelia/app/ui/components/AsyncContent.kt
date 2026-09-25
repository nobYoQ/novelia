package cc.novelia.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.feedback.MidoriSticker
import cc.novelia.app.ui.feedback.StickerAccent
import cc.novelia.app.ui.theme.MotionContent
import cc.novelia.app.ui.theme.appReducedMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * 页面共用的加载容器。key 表示内容身份，变化时清空结果；refreshKey 或内部重试只刷新同一内容。
 * 已有成功内容时刷新保留原组合和列表/编辑状态，失败显示附加错误，不退回整页空白。
 * load 必须可取消且自行切换耗时工作的调度器；取消不转换为错误页，结果发布前再次检查取消。
 */
@Composable fun <T> AsyncContent(key: Any?, load: suspend () -> T, modifier: Modifier = Modifier, refreshKey: Any? = Unit,
    initialResult: T? = null, onLoaded: (T) -> Unit = {}, content: @Composable (T, () -> Unit) -> Unit) {
    var refresh by remember(key) { mutableIntStateOf(0) }
    var result by remember(key) { mutableStateOf<Result<T>?>(initialResult?.let { Result.success(it) }) }
    var loading by remember(key) { mutableStateOf(initialResult == null) }
    var refreshError by remember(key) { mutableStateOf<Exception?>(null) }
    var skipInitialLoad by remember(key) { mutableStateOf(initialResult != null) }
    val currentLoad by rememberUpdatedState(load)
    LaunchedEffect(key, refreshKey, refresh) {
        if (skipInitialLoad) { skipInitialLoad = false; return@LaunchedEffect }
        loading = true
        refreshError = null
        try {
            val loaded = currentLoad()
            currentCoroutineContext().ensureActive()
            result = Result.success(loaded)
            onLoaded(loaded)
        } catch(e: CancellationException) {
            throw e
        } catch(e: Exception) {
            currentCoroutineContext().ensureActive()
            if(result?.isSuccess == true) refreshError = e else result = Result.failure(e)
        }
        loading = false
    }
    val retry: () -> Unit = { if(!loading) refresh++ }
    Box(modifier.fillMaxSize()) {
        val current = result
        when {
            current == null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) { if(!appReducedMotion()) CircularProgressIndicator(); Text("正在加载…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            current.isFailure -> if(loading) { if(appReducedMotion()) Text("正在加载…", Modifier.align(Alignment.Center)) else CircularProgressIndicator(Modifier.align(Alignment.Center)) } else EmptyState("暂时无法加载", current.exceptionOrNull().friendlyMessage(), Icons.Outlined.CloudOff, "重试", retry, sticker = MidoriSticker.Concerned)
            else -> {
                // Keep the same composition during refresh so list positions and editor state survive.
                MotionContent(key, Modifier.fillMaxSize(), animateInitial = false) {
                    content(current.getOrThrow(), retry)
                }
                if(loading) { if(appReducedMotion()) Text("正在刷新…", Modifier.align(Alignment.TopCenter)) else LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter)) }
                refreshError?.let { error ->
                    MotionContent(error, Modifier.align(Alignment.BottomCenter).padding(12.dp)) {
                        Snackbar(modifier = Modifier.heightIn(min = 64.dp), action = { TextButton(onClick = retry) { Text("重试") } }) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StickerAccent(MidoriSticker.Concerned, error, Modifier.size(40.dp))
                                Text("刷新未完成：${error.friendlyMessage()}", Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable fun rememberDebouncedQuery(query: String): String {
    val settled by produceState(query, query) {
        if(query.isNotBlank()) delay(180)
        value = query
    }
    return settled
}
