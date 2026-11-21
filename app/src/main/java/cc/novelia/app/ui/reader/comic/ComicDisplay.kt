@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.reader.comic

import android.graphics.Bitmap
import android.graphics.Color as Pixel
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.components.base.AppSheet
import cc.novelia.app.reader.ComicPage
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import coil.transform.Transformation
import java.io.File
import kotlinx.coroutines.*

private val pageIO = Dispatchers.IO.limitedParallelism(4)

@Composable internal fun ResolvedPage(page: ComicPage, index: Int, modifier: Modifier, darkBackground: Boolean = true,
    content: @Composable (ComicPage) -> Unit) {
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
            resolved == null -> PageLoading(darkBackground)
            resolved.isFailure -> PageError(index, darkBackground) { retry++ }
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
        Column(Modifier.fillMaxWidth().comicPanelHeight().testTag("comic-overview-panel")) {
            ComicSheetHeader("页缩略图", "共 ${pages.size} 页 · 当前第 ${selected + 1} 页", close)
            HorizontalDivider()
            val grid = rememberLazyGridState(initialFirstVisibleItemIndex = selected)
            LazyVerticalGrid(GridCells.Adaptive(96.dp), Modifier.weight(1f), state = grid, contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(pages.size, key = { it }) { index ->
                    val page = pages[index]
                    val active = index == selected
                    Surface(onClick = { select(index) }, shape = MaterialTheme.shapes.large,
                        color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(if (active) 2.dp else 1.dp,
                            if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.semantics { this.selected = active; contentDescription = "跳到第 ${index + 1} 页" }) {
                        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.fillMaxWidth().aspectRatio(.72f).clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                                if (page.text != null) Text(page.text, Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall,
                                    maxLines = 5, overflow = TextOverflow.Ellipsis)
                                else ResolvedPage(page, index, Modifier.fillMaxSize(), darkBackground = false) { p ->
                                    val ctx = LocalContext.current
                                    AsyncImage(remember(p.file, ctx) { ImageRequest.Builder(ctx).data(p.file).size(240, 336).build() },
                                        null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                }
                                if (active) Surface(Modifier.align(Alignment.TopEnd).padding(4.dp),
                                    shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary) {
                                    Icon(Icons.Outlined.Check, null, Modifier.padding(3.dp).size(16.dp))
                                }
                            }
                            val hit = sections.binarySearchBy(index) { it.first }
                            val section = sections.getOrNull(if (hit >= 0) hit else -hit - 2)
                            Text("${if (active) "当前 · " else ""}第 ${index + 1} 页", Modifier.padding(horizontal = 2.dp),
                                style = MaterialTheme.typography.labelMedium, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            if (sections.size > 1 && section != null) Text(section.title, Modifier.padding(start = 2.dp, end = 2.dp, bottom = 2.dp),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
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
