package cc.novelia.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import cc.novelia.app.reader.IllustrationTransform
import cc.novelia.app.reader.transformIllustration
import cc.novelia.app.ui.theme.LocalEInkMode
import cc.novelia.app.ui.theme.appReducedMotion
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlin.math.roundToInt

@Composable
internal fun IllustrationViewer(model: Any, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var retry by remember(model) { mutableIntStateOf(0) }
    // Bound decoding memory while retaining enough resolution to inspect illustrations.
    val request = remember(context, model, retry) {
        ImageRequest.Builder(context).data(model).size(4096, 4096).build()
    }
    var loading by remember(model, retry) { mutableStateOf(true) }
    var failed by remember(model, retry) { mutableStateOf(false) }
    var imageSize by remember(model) { mutableStateOf(Size.Zero) }
    var viewport by remember { mutableStateOf(Size.Zero) }
    var transform by remember(model, retry) { mutableStateOf(IllustrationTransform()) }
    val ready = !loading && !failed
    val eInk = LocalEInkMode.current
    val reducedMotion = appReducedMotion()
    fun zoom(factor: Float, anchor: Offset = Offset(viewport.width / 2, viewport.height / 2), pan: Offset = Offset.Zero) {
        if(ready) transform = transformIllustration(transform, viewport, imageSize, anchor, factor, pan)
    }
    AppDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
        }
        Surface(Modifier.fillMaxSize(), color = Color(0xFF080B0A), contentColor = Color.White) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("插图", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭插图") }
                }
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(Color.Black)
                    .onSizeChanged {
                        val next = Size(it.width.toFloat(), it.height.toFloat())
                        if(next != viewport) { viewport = next; transform = IllustrationTransform() }
                    }
                    .testTag("illustration-viewport")
                    .semantics { stateDescription = "${(transform.scale * 100).roundToInt()}%" }
                    .pointerInput(ready, imageSize, viewport, eInk) {
                        if(ready && !eInk) detectTransformGestures { centroid, pan, scale, _ -> zoom(scale, centroid, pan) }
                    }
                    .pointerInput(ready, imageSize, viewport) {
                        if(ready) detectTapGestures(onDoubleTap = { point ->
                            if(transform.scale > 1f) transform = IllustrationTransform() else zoom(2.5f, point)
                        })
                    }, contentAlignment = Alignment.Center) {
                    // Coil treats equivalent requests as the same model; retry needs a fresh painter.
                    key(retry) { AsyncImage(request, "放大的小说插图", Modifier.fillMaxSize().graphicsLayer {
                        scaleX = transform.scale; scaleY = transform.scale
                        translationX = transform.offset.x; translationY = transform.offset.y
                    }, contentScale = ContentScale.Fit,
                        onLoading = { loading = true; failed = false },
                        onSuccess = {
                            imageSize = Size(it.result.drawable.intrinsicWidth.coerceAtLeast(1).toFloat(), it.result.drawable.intrinsicHeight.coerceAtLeast(1).toFloat())
                            loading = false; failed = false
                        },
                        onError = { loading = false; failed = true },
                    ) }
                    if(loading) { if(reducedMotion) Text("正在加载插图…") else CircularProgressIndicator(Modifier.size(32.dp), color = Color.White) }
                    if(failed) Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("插图暂时无法加载", color = Color.White)
                        FilledTonalButton(onClick = { retry++ }) { Text("重试") }
                    }
                }
                val buttonColors = IconButtonDefaults.iconButtonColors(contentColor = Color.White, disabledContentColor = Color.White.copy(alpha = .35f))
                if(eInk && transform.scale > 1f) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    listOf(Triple(Icons.Outlined.KeyboardArrowUp, "查看插图上方", Offset(0f, viewport.height * .7f)),
                        Triple(Icons.Outlined.KeyboardArrowDown, "查看插图下方", Offset(0f, -viewport.height * .7f)),
                        Triple(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "查看插图左侧", Offset(viewport.width * .7f, 0f)),
                        Triple(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "查看插图右侧", Offset(-viewport.width * .7f, 0f))).forEach { (icon, label, pan) ->
                        IconButton(onClick = { zoom(1f, pan = pan) }, enabled = ready, colors = buttonColors) { Icon(icon, label) }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { zoom(1 / 1.5f) }, enabled = ready && transform.scale > 1f, colors = buttonColors) { Icon(Icons.Outlined.ZoomOut, "缩小插图") }
                    Text("${(transform.scale * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = { zoom(1.5f) }, enabled = ready && transform.scale < 5f, colors = buttonColors) { Icon(Icons.Outlined.ZoomIn, "放大插图") }
                    IconButton(onClick = { transform = IllustrationTransform() }, enabled = ready && transform.scale > 1f, colors = buttonColors) { Icon(Icons.Outlined.RestartAlt, "还原插图") }
                }
            }
        }
    }
}
