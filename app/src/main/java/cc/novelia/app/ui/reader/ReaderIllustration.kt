@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cc.novelia.app.reader.*
import cc.novelia.app.ui.components.IllustrationViewer
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.appReducedMotion
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable internal fun ReaderIllustration(model: Any, foreground: Color, onToggleMenu: () -> Unit) {
    val context = LocalContext.current
    val animate = !appReducedMotion()
    var retry by remember(model) { mutableIntStateOf(0) }
    val request = remember(context, model, animate, retry) { ImageRequest.Builder(context).data(model).setParameter("readerRetry", retry).crossfade(if(animate) AppMotion.Release else 0).build() }
    var loading by remember(model) { mutableStateOf(true) }
    var failed by remember(model) { mutableStateOf(false) }
    var expanded by remember(model) { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Reserve a portrait illustration frame before decoding so incoming images cannot shift later paragraphs.
        Box(Modifier.fillMaxWidth().height((maxWidth * 1.35f).coerceIn(180.dp, 900.dp))
            .background(foreground.copy(alpha = .035f))
            .combinedClickable(onClickLabel = "显示或收起阅读工具栏", onClick = onToggleMenu,
                onLongClickLabel = "放大查看插图", onLongClick = { expanded = true }), contentAlignment = Alignment.Center) {
            AsyncImage(request, "小说插图", Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                onLoading = { loading = true; failed = false }, onSuccess = { loading = false; failed = false }, onError = { loading = false; failed = true })
            if(loading) { if(!animate) Text("正在加载插图…", color = foreground) else CircularProgressIndicator(Modifier.size(28.dp), color = foreground.copy(alpha = .65f), strokeWidth = 2.dp) }
            if(failed) Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("插图暂时无法加载", style = MaterialTheme.typography.bodyMedium, color = foreground.copy(alpha = .7f))
                TextButton(onClick = { retry++ }) { Text("重新加载插图", color = foreground) }
            }
        }
    }
    if(expanded) IllustrationViewer(model) { expanded = false }
}
