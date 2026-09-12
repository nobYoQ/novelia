@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.novelia.app.data.*
import coil.compose.AsyncImage
import coil.decode.DataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun Screen(title: String, back: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = { if(back != null) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } }, actions = actions) }, content = content)
}
@Composable fun EmptyState(title: String, message: String, icon: ImageVector = Icons.Outlined.AutoStories, action: String? = null, onAction: () -> Unit = {}, sticker: MidoriSticker? = null) {
    MotionContent(Unit, Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if(sticker != null) StickerAccent(sticker, modifier = Modifier.size(112.dp))
            else Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(28.dp), modifier = Modifier.size(88.dp)) { Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer) } }
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if(action != null) FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable fun <T> AsyncContent(key: Any?, load: suspend () -> T, modifier: Modifier = Modifier, refreshKey: Any? = Unit, content: @Composable (T, () -> Unit) -> Unit) {
    var refresh by remember(key) { mutableIntStateOf(0) }
    var result by remember(key) { mutableStateOf<Result<T>?>(null) }
    var loading by remember(key) { mutableStateOf(true) }
    var refreshError by remember(key) { mutableStateOf<Exception?>(null) }
    val currentLoad by rememberUpdatedState(load)
    LaunchedEffect(key, refreshKey, refresh) {
        loading = true
        refreshError = null
        try {
            val loaded = currentLoad()
            currentCoroutineContext().ensureActive()
            result = Result.success(loaded)
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
            current == null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) { CircularProgressIndicator(); Text("正在加载…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            current.isFailure -> if(loading) CircularProgressIndicator(Modifier.align(Alignment.Center)) else EmptyState("暂时无法加载", current.exceptionOrNull().friendlyMessage(), Icons.Outlined.CloudOff, "重试", retry, sticker = MidoriSticker.Concerned)
            else -> {
                // Keep the same composition during refresh so list positions and editor state survive.
                MotionContent(key, Modifier.fillMaxSize()) {
                    content(current.getOrThrow(), retry)
                }
                if(loading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
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
fun Throwable?.friendlyMessage(): String = when(this) { is ApiException -> message; is java.net.UnknownHostException -> "网络不可用，请检查连接。已缓存的章节仍可在书架中阅读。"; is java.net.SocketTimeoutException -> "连接超时，请稍后重试"; is IllegalArgumentException -> message?.take(200) ?: "输入内容或文件格式不符合要求"; else -> "操作未完成，请检查网络或文件内容后重试" }
@Composable fun BookCover(book: BookCard, modifier: Modifier = Modifier) {
    val source = book.cover?.takeIf { it.isNotBlank() }
    var loaded by remember(source) { mutableStateOf(false) }
    Box(modifier.width(76.dp).height(104.dp).clip(RoundedCornerShape(12.dp)).semantics {
        contentDescription = "${book.title} ${if(loaded) "封面" else "默认封面"}"
    }, contentAlignment = Alignment.Center) {
        DefaultBookCover(book, Modifier.matchParentSize().clearAndSetSemantics {})
        if(source != null) {
            val reducedMotion = LocalReducedMotion.current
            var memoryCached by remember(source) { mutableStateOf(false) }
            val coverAlpha = remember(source) { Animatable(0f) }
            LaunchedEffect(source, loaded, memoryCached, reducedMotion) {
                if(!loaded) coverAlpha.snapTo(0f)
                else if(reducedMotion || memoryCached) coverAlpha.snapTo(1f)
                else coverAlpha.animateTo(1f, tween(180))
            }
            AsyncImage(
                source, null,
                Modifier.fillMaxSize().graphicsLayer { alpha = if(reducedMotion || memoryCached) 1f else coverAlpha.value },
                contentScale = ContentScale.Crop,
                onLoading = { loaded = false; memoryCached = false },
                onSuccess = { memoryCached = it.result.dataSource == DataSource.MEMORY_CACHE; loaded = true },
                onError = { loaded = false; memoryCached = false },
            )
        }
    }
}
@Composable fun BookRow(book: BookCard, onClick: () -> Unit, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().motionClickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        BookCover(book)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(book.subtitle.ifBlank { providers[book.ref.provider] ?: "本地小说" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if(book.total > 0) Text(if(book.ref.isWenku) "${book.total} 个分卷文件" else "${book.translated} / ${book.total} 章有译文", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            else if(book.originalTitle != book.title) Text(book.originalTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}
@Composable fun SectionTitle(title: String, detail: String? = null, onClick: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if(detail != null) TextButton(onClick = onClick) { Text(detail) }
    }
}
@Composable fun ChoiceRow(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val reducedMotion = LocalReducedMotion.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { i, title ->
                key(i, title) {
                    val interactionSource = remember { MutableInteractionSource() }
                    val checked = selected == i
                    val checkProgress = animateFloatAsState(if(checked) 1f else 0f, tween(if(reducedMotion) 0 else 160), label = "choice check")
                    FilterChip(
                        checked, onClick = { onSelect(i) }, label = { Text(title) },
                        modifier = Modifier.pressFeedback(interactionSource), interactionSource = interactionSource,
                        leadingIcon = {
                            // Reserve the slot so selecting a chip cannot move its neighbours.
                            Icon(Icons.Outlined.Check, null, Modifier.size(18.dp).graphicsLayer {
                                val progress = if(reducedMotion) if(checked) 1f else 0f else checkProgress.value
                                alpha = progress
                                scaleX = .7f + .3f * progress
                                scaleY = scaleX
                            })
                        },
                    )
                }
            }
        }
    }
}
@Composable fun MenuRow(title: String, description: String, icon: ImageVector, onClick: () -> Unit, trailing: @Composable (() -> Unit)? = null) {
    ListItem(headlineContent = { Text(title) }, supportingContent = { if(description.isNotEmpty()) Text(description) }, leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }, trailingContent = trailing, modifier = Modifier.motionClickable(onClick = onClick).heightIn(min = 64.dp))
}
@Composable fun TextPrompt(title: String, label: String, initial: String = "", onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { OutlinedTextField(text, { text = it }, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 6) }, confirmButton = { TextButton(onClick = { onSave(text.trim()); onDismiss() }, enabled = text.isNotBlank()) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
@Composable fun ConfirmDialog(title: String, message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(message) }, confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text("确认") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd")
fun displayDate(seconds: Long): String = runCatching { dateFormatter.format(Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault())) }.getOrDefault("")
