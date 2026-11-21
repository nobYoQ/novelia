@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.reader.comic

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.ComicReadingMode
import cc.novelia.app.reader.ComicPage
import cc.novelia.app.reader.ComicStripLayout
import cc.novelia.app.ui.components.base.AppIconButton
import cc.novelia.app.ui.components.base.AppSlider
import cc.novelia.app.ui.components.media.IllustrationViewer
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val ComicReadingMode.label: String get() = when (this) {
    ComicReadingMode.RightToLeft -> "从右向左"
    ComicReadingMode.LeftToRight -> "从左向右"
    ComicReadingMode.Webtoon -> "纵向条漫"
}

@Composable
internal fun ComicReader(
    title: String, pages: List<ComicPage>, mode: ComicReadingMode, initialPage: Int, initialOffset: Int,
    onMode: (ComicReadingMode) -> Unit, onPosition: (Int, Int, Boolean) -> Unit, onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by rememberSaveable { mutableStateOf(true) }
    var zoomPage by rememberSaveable { mutableStateOf<Int?>(null) }
    var anchorPage by rememberSaveable { mutableIntStateOf(initialPage) }
    var anchorOffset by rememberSaveable { mutableIntStateOf(initialOffset.coerceIn(0, pages[initialPage].height - 1)) }
    val latestPosition by rememberUpdatedState(onPosition)
    val webtoon = mode == ComicReadingMode.Webtoon
    val rtl = mode == ComicReadingMode.RightToLeft
    val layout = remember(pages) { ComicStripLayout(pages) }
    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val width = constraints.maxWidth.coerceAtLeast(1)
        key(mode) {
            val pager = rememberPagerState(initialPage = anchorPage) { pages.size }
            val first = layout.stripAt(anchorPage, anchorOffset)
            val list = rememberLazyListState(first,
                ((anchorOffset - layout[first].top).coerceAtLeast(0) * width.toDouble() / pages[anchorPage].width).roundToInt())
            val scope = rememberCoroutineScope()
            val page by remember { derivedStateOf {
                if (webtoon) layout[list.firstVisibleItemIndex].page else pager.currentPage
            } }
            fun position(): ComicPosition? {
                if (webtoon) {
                    if (list.layoutInfo.visibleItemsInfo.isEmpty()) return null
                    val strip = layout[list.firstVisibleItemIndex]
                    val offset = (strip.top + list.firstVisibleItemScrollOffset * pages[strip.page].width.toDouble() / width)
                        .roundToInt().coerceIn(strip.top, strip.bottom - 1)
                    return ComicPosition(strip.page, offset, !list.canScrollForward)
                }
                return ComicPosition(pager.currentPage, 0, pager.currentPage == pages.lastIndex)
            }
            fun save(position: ComicPosition?) {
                if (position == null) return
                anchorPage = position.page
                anchorOffset = position.offset
                latestPosition(position.page, position.offset, position.completed)
            }
            // 只在停稳或主动离开时写入书架，拖动长图不会逐像素写盘。
            LaunchedEffect(width) {
                snapshotFlow {
                    if (list.isScrollInProgress || pager.isScrollInProgress) null else position()
                }.collect(::save)
            }
            val saveCurrent by rememberUpdatedState { save(position()) }
            DisposableEffect(Unit) { onDispose { saveCurrent() } }

            fun seek(target: Int) {
                scope.launch {
                    if (webtoon) list.scrollToItem(layout.firstStrip[target])
                    else pager.scrollToPage(target)
                }
            }
            fun step(delta: Int) {
                if (webtoon) scope.launch { list.scrollBy(delta * list.layoutInfo.viewportSize.height * .9f) }
                else seek((page + delta).coerceIn(pages.indices))
            }
            if (webtoon) {
                // 短末页也能定位到屏顶，从分页跳入条漫时保留相同页码。
                val last = pages.last()
                val tail = (constraints.maxHeight - width * last.height.toDouble() / last.width).coerceAtLeast(0.0)
                LazyColumn(state = list, modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = with(LocalDensity.current) { tail.toFloat().toDp() })) {
                    items(layout.size) { index ->
                        val strip = layout[index]
                        val source = pages[strip.page]
                        ComicStripImage(source, strip, width, Modifier.fillMaxWidth()
                            .aspectRatio(source.width.toFloat() / (strip.bottom - strip.top))
                            .pointerInput(strip.page) {
                                detectTapGestures(onTap = { menu = !menu }, onDoubleTap = { zoomPage = strip.page })
                            })
                    }
                }
            } else {
                HorizontalPager(pager, modifier = Modifier.fillMaxSize(),
                    reverseLayout = rtl != (LocalLayoutDirection.current == LayoutDirection.Rtl)) { index ->
                    ComicPagedImage(pages[index], index, onTap = { fraction ->
                        when {
                            fraction < .25f -> step(if (rtl) 1 else -1)
                            fraction > .75f -> step(if (rtl) -1 else 1)
                            else -> menu = !menu
                        }
                    })
                }
            }
            if (menu) {
                ComicHeader(title, mode, onMode = { next ->
                    save(position())
                    onMode(next)
                }, onBack = onBack, modifier = Modifier.align(Alignment.TopCenter))
                ComicPageControls(page, pages.size, rtl, webtoon,
                    canBack = if (webtoon) list.canScrollBackward else page > 0,
                    canForward = if (webtoon) list.canScrollForward else page < pages.lastIndex,
                    step = ::step, seek = ::seek, zoom = { zoomPage = page },
                    modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }
    zoomPage?.let { index -> IllustrationViewer(pages[index].file, "第 ${index + 1} 页") { zoomPage = null } }
}

private data class ComicPosition(val page: Int, val offset: Int, val completed: Boolean)

@Composable private fun ComicHeader(title: String, mode: ComicReadingMode, onMode: (ComicReadingMode) -> Unit,
    onBack: () -> Unit, modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Surface(modifier.fillMaxWidth(), color = Color.Black.copy(alpha = .82f), contentColor = Color.White) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
            Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box {
                TextButton(onClick = { expanded = true }) { Text(mode.label, color = Color.White) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    ComicReadingMode.entries.forEach { choice ->
                        DropdownMenuItem(text = { Text(choice.label) }, onClick = { expanded = false; onMode(choice) },
                            trailingIcon = { if (mode == choice) Icon(Icons.Outlined.Check, "当前模式") })
                    }
                }
            }
        }
    }
}

@Composable private fun ComicPageControls(page: Int, count: Int, rtl: Boolean, webtoon: Boolean,
    canBack: Boolean, canForward: Boolean, step: (Int) -> Unit, seek: (Int) -> Unit, zoom: () -> Unit, modifier: Modifier) {
    var pending by remember { mutableStateOf<Int?>(null) }
    val unit = if (webtoon) "屏" else "页"
    Surface(modifier.fillMaxWidth(), color = Color.Black.copy(alpha = .82f), contentColor = Color.White) {
        CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIconButton(onClick = { step(-1) }, enabled = canBack) {
                    Icon(if (webtoon) Icons.Outlined.ExpandLess else if (rtl) Icons.Outlined.ChevronRight else Icons.Outlined.ChevronLeft, "上一$unit")
                }
                Text("${(pending ?: page) + 1} / $count", style = MaterialTheme.typography.labelLarge)
                if (count > 1) AppSlider((pending ?: page).toFloat(), { pending = it.roundToInt() },
                    Modifier.weight(1f).padding(horizontal = 6.dp).semantics { contentDescription = "漫画页码" },
                    valueRange = 0f..(count - 1).toFloat(), onValueChangeFinished = { pending?.let(seek); pending = null })
                else Spacer(Modifier.weight(1f))
                AppIconButton(onClick = zoom) { Icon(Icons.Outlined.ZoomIn, "放大当前页") }
                AppIconButton(onClick = { step(1) }, enabled = canForward) {
                    Icon(if (webtoon) Icons.Outlined.ExpandMore else if (rtl) Icons.Outlined.ChevronLeft else Icons.Outlined.ChevronRight, "下一$unit")
                }
            }
        }
    }
}
