package cc.novelia.app.ui.reader.comic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import cc.novelia.app.reader.ComicPage
import cc.novelia.app.reader.ComicStrip
import cc.novelia.app.reader.IllustrationTransform
import cc.novelia.app.reader.transformIllustration
import cc.novelia.app.ui.components.media.RecoveringAsyncImage
import cc.novelia.app.ui.theme.appReducedMotion
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.roundToInt

@Composable internal fun ComicPagedImage(page: ComicPage, index: Int, onTap: (Float) -> Unit) {
    var viewport by remember { mutableStateOf(Size.Zero) }
    var transform by remember(page, viewport) { mutableStateOf(IllustrationTransform()) }
    val image = Size(page.width.toFloat(), page.height.toFloat())
    val gestures = rememberTransformableState { zoom, pan, _ ->
        transform = transformIllustration(transform, viewport, image, zoom = zoom, pan = pan)
    }
    val latestTap by rememberUpdatedState(onTap)
    Box(Modifier.fillMaxSize().clipToBounds()
        .semantics { stateDescription = "${(transform.scale * 100).roundToInt()}%" }
        .onSizeChanged { viewport = Size(it.width.toFloat(), it.height.toFloat()) }
        .transformable(state = gestures, canPan = { transform.scale > 1f })
        .pointerInput(page, viewport) {
            detectTapGestures(onTap = { latestTap(it.x / size.width.coerceAtLeast(1)) }, onDoubleTap = {
                transform = if (transform.scale > 1f) IllustrationTransform()
                    else transformIllustration(transform, viewport, image, centroid = it, zoom = 2.5f)
            })
        }) {
        ComicImage(page, index, Modifier.fillMaxSize().graphicsLayer {
            scaleX = transform.scale
            scaleY = transform.scale
            translationX = transform.offset.x
            translationY = transform.offset.y
        })
    }
}

@Composable internal fun ComicStripImage(page: ComicPage, strip: ComicStrip, width: Int, modifier: Modifier) {
    if (strip.top == 0 && strip.bottom == page.height) {
        ComicImage(page, strip.page, modifier)
        return
    }
    var retry by remember(page, strip) { mutableIntStateOf(0) }
    val bitmap by produceState<Result<Bitmap>?>(null, page, strip, width, retry) {
        value = null
        value = withContext(Dispatchers.IO) {
            try { Result.success(decodeStrip(page, strip, width)) }
            catch (e: IOException) { Result.failure(e) }
            catch (e: IllegalArgumentException) { Result.failure(e) }
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val part = bitmap
        when {
            part == null -> PageLoading()
            part.isFailure -> PageError(strip.page) { retry++ }
            else -> Image(part.getOrThrow().asImageBitmap(), "第 ${strip.page + 1} 页",
                Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        }
    }
}

@Suppress("DEPRECATION") // API 26–30 使用带 isShareable 参数的构造方法。
private fun decodeStrip(page: ComicPage, strip: ComicStrip, width: Int): Bitmap {
    val limit = width.coerceIn(1, 2048)
    var sample = 1
    while (page.width / sample > limit * 1.5f) sample *= 2
    val decoder = BitmapRegionDecoder.newInstance(page.file.path, false) ?: throw IOException("无法解码漫画页")
    return try {
        // 根据 EXIF 将显示方向上的纵向区间映射回原文件，解码后再校正方向。
        val region = when (page.orientation) {
            3, 4 -> Rect(0, decoder.height - strip.bottom, decoder.width, decoder.height - strip.top)
            5, 6 -> Rect(strip.top, 0, strip.bottom, decoder.height)
            7, 8 -> Rect(decoder.width - strip.bottom, 0, decoder.width - strip.top, decoder.height)
            else -> Rect(0, strip.top, decoder.width, strip.bottom)
        }
        val bitmap = decoder.decodeRegion(region, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("无法解码漫画区域")
        val matrix = Matrix().apply {
            if (page.orientation in listOf(2, 4, 5, 7)) setScale(-1f, 1f)
            postRotate(when (page.orientation) { 3, 4 -> 180f; 5, 8 -> 270f; 6, 7 -> 90f; else -> 0f })
        }
        if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    } finally { decoder.recycle() }
}

@Composable private fun ComicImage(page: ComicPage, index: Int, modifier: Modifier) {
    val context = LocalContext.current
    val request = remember(page, context) {
        ImageRequest.Builder(context).data(page.file).size(3072, 3072).build()
    }
    var retry by remember(page) { mutableIntStateOf(0) }
    key(page, retry) {
        var loading by remember { mutableStateOf(true) }
        var failed by remember { mutableStateOf(false) }
        Box(modifier, contentAlignment = Alignment.Center) {
            RecoveringAsyncImage(request, "第 ${index + 1} 页", Modifier.fillMaxSize(),
                onSuccess = { loading = false }, onError = { loading = false; failed = true })
            if (loading) PageLoading()
            if (failed) PageError(index) { retry++ }
        }
    }
}

@Composable private fun PageLoading() {
    if (appReducedMotion()) Text("正在加载…", color = Color.White)
    else CircularProgressIndicator(Modifier.size(24.dp), color = Color.White)
}

@Composable private fun PageError(index: Int, retry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("第 ${index + 1} 页加载失败", color = Color.White)
        TextButton(onClick = retry) { Text("重试") }
    }
}
