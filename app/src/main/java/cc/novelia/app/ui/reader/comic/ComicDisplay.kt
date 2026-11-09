@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.reader.comic

import android.graphics.Bitmap
import android.graphics.Color as Pixel
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.components.base.AppSheet
import cc.novelia.app.data.model.*
import cc.novelia.app.reader.ComicPage
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import coil.transform.Transformation
import java.io.File
import kotlinx.coroutines.*

private val pageIO = Dispatchers.IO.limitedParallelism(4)

@Composable internal fun ResolvedPage(page: ComicPage, index: Int, modifier: Modifier, content: @Composable (ComicPage) -> Unit) {
    val load = page.load
    if (load == null) { Box(modifier, contentAlignment = Alignment.Center) { content(page) }; return }
    var retry by remember(page) { mutableIntStateOf(0) }
    val result by produceState<Result<File>?>(null, page, retry) {
        value = null
        value = withContext(pageIO) {
            try { Result.success(runInterruptible { load() }) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Result.failure(e) }
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val resolved = result
        when {
            resolved == null -> PageLoading()
            resolved.isFailure -> PageError(index) { retry++ }
            else -> content(remember(page, resolved) { page.copy(file = resolved.getOrThrow(), load = null) })
        }
    }
}

@Composable internal fun ComicTextPage(text: String, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(text, Modifier.verticalScroll(rememberScrollState()).padding(24.dp), color = Color.White,
            style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable internal fun ComicOverview(pages: List<ComicPage>, sections: List<ComicSection>, selected: Int,
    select: (Int) -> Unit, close: () -> Unit) {
    AppSheet(onDismissRequest = close) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.78f).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("页缩略图", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = close) { Text("关闭") }
            }
            val grid = rememberLazyGridState(initialFirstVisibleItemIndex = selected)
            LazyVerticalGrid(GridCells.Adaptive(80.dp), Modifier.weight(1f), state = grid,
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(pages.size) { index ->
                    val page = pages[index]
                    Column(Modifier.clickable { select(index) }.padding(3.dp)) {
                        val tile = Modifier.fillMaxWidth().aspectRatio(.72f).background(MaterialTheme.colorScheme.surfaceVariant)
                            .then(if (index == selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary) else Modifier)
                        if (page.text != null) Box(tile) { Text(page.text, Modifier.padding(4.dp), maxLines = 5, overflow = TextOverflow.Ellipsis) }
                        else ResolvedPage(page, index, tile) { p ->
                            val ctx = LocalContext.current
                            AsyncImage(remember(p.file, ctx) { ImageRequest.Builder(ctx).data(p.file).size(192, 260).build() },
                                "跳到第 " + (index + 1) + " 页", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        }
                        val section = sections.lastOrNull { it.first <= index }
                        val label = if (sections.size > 1 && section != null) section.title + " · " + (index - section.first + 1) else (index + 1).toString()
                        Text(if (index == selected) "当前 · " + label else label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable internal fun ComicDisplaySettings(prefs: ComicPreferences, default: ComicReadingMode,
    setDefault: (ComicReadingMode) -> Unit, set: (ComicPreferences) -> Unit, details: () -> Unit, close: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text("漫画显示设置") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            TextButton(onClick = details) { Text("书籍详情与目录") }
            var expanded by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { expanded = true }) { Text("全局默认 · " + default.label) }
                DropdownMenu(expanded, { expanded = false }) {
                    ComicReadingMode.entries.forEach { m ->
                        DropdownMenuItem(text = { Text(m.label) }, onClick = { expanded = false; setDefault(m) })
                    }
                }
            }
            TextButton(enabled = prefs.mode != null, onClick = { set(prefs.copy(mode = null)) }) {
                Text(if (prefs.mode == null) "本书跟随默认方向" else "本书恢复默认方向")
            }
            Text("本书分页显示", style = MaterialTheme.typography.titleSmall)
            @Composable fun option(label: String, checked: Boolean, change: (Boolean) -> Unit) {
                Row(Modifier.fillMaxWidth().clickable { change(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f)); Switch(checked, change)
                }
            }
            option("适合宽度", prefs.fitWidth) { set(prefs.copy(fitWidth = it)) }
            option("裁去白边", prefs.cropBorders) { set(prefs.copy(cropBorders = it)) }
            option("按阅读方向拆分横跨页", prefs.splitWide) { set(prefs.copy(splitWide = it)) }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("完成") } })
}

/** Detect contiguous near-white margins conservatively; preserve a small gutter and blank pages. */
internal data class ComicPageTransform(val crop: Boolean, val half: Int) : Transformation {
    override val cacheKey = "comic-page-v1:" + crop + ":" + half
    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        var left = if (half == 1) input.width / 2 else 0
        var right = if (half == 0) maxOf(1, input.width / 2) else input.width
        var top = 0
        var bottom = input.height
        if (crop) {
            fun white(x: Int, y: Int): Boolean {
                val color = input.getPixel(x, y)
                return Pixel.alpha(color) < 16 || minOf(Pixel.red(color), Pixel.green(color), Pixel.blue(color)) >= 246
            }
            val dx = maxOf(1, (right - left) / 256)
            val dy = maxOf(1, input.height / 256)
            val maxX = (right - left) / 4
            val maxY = input.height / 4
            val origLeft = left
            val origRight = right
            while (left - origLeft < maxX && (0 until bottom step dy).all { white(left, it) }) left++
            while (origRight - right < maxX && (0 until bottom step dy).all { white(right - 1, it) }) right--
            while (top < maxY && (left until right step dx).all { white(it, top) }) top++
            while (input.height - bottom < maxY && (left until right step dx).all { white(it, bottom - 1) }) bottom--
            left = maxOf(origLeft, left - 2); right = minOf(origRight, right + 2)
            top = maxOf(0, top - 2); bottom = minOf(input.height, bottom + 2)
        }
        return Bitmap.createBitmap(input, left, top, right - left, bottom - top)
    }
}
