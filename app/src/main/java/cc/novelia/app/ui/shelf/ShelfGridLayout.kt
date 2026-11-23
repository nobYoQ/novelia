package cc.novelia.app.ui.shelf

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.data.model.ShelfGridDensity
import cc.novelia.app.ui.components.base.*
import cc.novelia.app.ui.components.book.BookCover
import cc.novelia.app.ui.components.book.BookRowStatus
import cc.novelia.app.ui.theme.*
import kotlin.math.abs

private val volumeNumber = Regex("第\\s*[0-9一二三四五六七八九十百零〇]+\\s*[卷巻]")
internal fun shelfVolumeProgressLabel(parentTitle: String, volumeTitle: String, progress: String): String {
    val title = volumeTitle.removePrefix(parentTitle).trim().ifBlank { volumeTitle }
    // 只缩短文件名里明确写出的卷号，番外、合订本等保留原名，不用列表序号冒充卷号。
    return "${volumeNumber.find(title)?.value ?: title} · $progress"
}

@Composable internal fun ShelfLayoutToggle(grid: Boolean, onToggle: () -> Unit, prefix: String) {
    AppIconButton(onToggle, Modifier.size(48.dp).testTag("$prefix-layout-toggle").semantics {
        stateDescription = if(grid) "网格布局" else "列表布局"
    }) { Icon(if(grid) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView,
        if(grid) "切换为列表布局" else "切换为网格布局") }
}

@Composable internal fun shelfGridColumns(width: Dp, density: ShelfGridDensity): Int {
    val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 1.3f)
    val preferred = density.columns ?: return ((width - 28.dp) / (172.dp * fontScale)).toInt().coerceAtLeast(2)
    val fitting = ((width - 28.dp) / (64.dp * fontScale + 12.dp)).toInt().coerceAtLeast(2)
    return preferred.coerceAtMost(fitting)
}

@Composable internal fun compactShelfGridCards(width: Dp, columns: Int): Boolean =
    (width - 40.dp - 12.dp * (columns - 1)) / columns < 120.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.3f)

@Composable internal fun ShelfLayoutTransition(grid: Boolean, modifier: Modifier = Modifier,
    content: @Composable (Boolean) -> Unit) {
    Box(modifier) {
        if(appReducedMotion()) content(grid)
        else AnimatedContent(grid, Modifier.fillMaxSize(), transitionSpec = {
            ((fadeIn(tween(AppMotion.Panel, delayMillis = 40)) + scaleIn(tween(AppMotion.Panel), initialScale = .96f)) togetherWith
                (fadeOut(tween(AppMotion.Exit)) + scaleOut(tween(AppMotion.Exit), targetScale = .98f)))
                .using(SizeTransform(clip = false))
        }, label = "shelf-layout") { content(it) }
    }
}

internal class ShelfLayoutState(val list: LazyListState, val grid: LazyListState) {
    fun current(grid: Boolean) = if(grid) this.grid else list
}

/** 切换以当前屏顶作品为锚点；展开子卷切到网格时定位其父作品，不按旧行号跳转。 */
@Composable internal fun rememberShelfLayoutState(
    grid: Boolean, listKeys: List<String>, gridKeys: List<String>, columns: Int,
    parents: Map<String, String> = emptyMap(), leadingItems: Int = 0,
    listState: LazyListState = rememberLazyListState(),
    restoredGridState: LazyListState? = null,
): ShelfLayoutState {
    val gridState = restoredGridState ?: rememberLazyListState()
    var previousList by remember { mutableStateOf(listState) }
    LaunchedEffect(listState) {
        // 未提供分页快照时保留原有重置规则；云端已恢复的网格不能再被重置到顶部。
        if(previousList !== listState) {
            if(restoredGridState == null) gridState.scrollToItem(0)
            previousList = listState
        }
    }
    val state = remember(listState, gridState) { ShelfLayoutState(listState, gridState) }
    var previousGrid by remember { mutableStateOf(grid) }
    var previousColumns by remember { mutableIntStateOf(columns) }
    LaunchedEffect(grid, columns) {
        if(previousGrid != grid || (grid && previousColumns != columns)) {
            val source = state.current(previousGrid)
            val target = state.current(grid)
            val allKeys = listKeys.toSet() + gridKeys
            val anchor = source.layoutInfo.visibleItemsInfo.firstOrNull { it.key in allKeys }
            val key = anchor?.key as? String
            val targetKey = if(grid) parents[key] ?: key else key
            val index = (if(grid) gridKeys else listKeys).indexOf(targetKey)
            if(index >= 0) target.scrollToItem(leadingItems + if(grid) index / columns else index,
                if(grid == previousGrid) source.firstVisibleItemScrollOffset else 0)
        }
        previousGrid = grid; previousColumns = columns
    }
    return state
}

/** 网格仍按 LazyColumn 的封面行绘制，命中必须同时考虑横向列；空白格不会选中邻书。 */
@Composable internal fun rememberShelfGridHitTest(list: LazyListState, rows: List<List<String>>, columns: Int): (Offset, Boolean) -> String? {
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val inset = with(density) { 20.dp.toPx() }
    val gap = with(density) { 12.dp.toPx() }
    val rowKeys = remember(rows) { rows.associateBy { it.first() } }
    return { point, nearest ->
        val layout = list.layoutInfo
        val y = point.y + layout.viewportStartOffset
        val visible = layout.visibleItemsInfo.filter { it.key in rowKeys }
        val row = visible.firstOrNull { y >= it.offset && y < it.offset + it.size }
            ?: if(nearest) visible.minByOrNull { abs(y - (it.offset + it.size / 2f)) } else null
        val entries = row?.let { rowKeys[it.key] }
        val width = layout.viewportSize.width.toFloat()
        val cell = (width - inset * 2 - gap * (columns - 1)) / columns
        val x = (if(rtl) width - point.x else point.x) - inset
        val column = if(cell > 0) ((x + if(nearest) gap / 2 else 0f) / (cell + gap)).toInt() else -1
        if(entries == null || cell <= 0 || (!nearest && (x < 0 || x >= width - inset * 2 || x % (cell + gap) > cell))) null
        else entries.getOrNull(if(nearest) column.coerceIn(0, entries.lastIndex) else column)
    }
}

@Composable internal fun ShelfCoverCard(
    book: BookCard, status: BookRowStatus, onOpen: () -> Unit, modifier: Modifier = Modifier,
    selecting: Boolean = false, selected: Boolean = false, enabled: Boolean = true,
    pinned: Boolean = false, onToggle: () -> Unit = onOpen,
    volumeLabel: String? = null, onVolumes: (() -> Unit)? = null, progressLabel: String? = null,
    compact: Boolean = false,
    authorLabel: String? = null,
    footer: @Composable (() -> Unit)? = null,
) {
    val eInk = LocalEInkMode.current
    val largeText = LocalDensity.current.fontScale > 1.3f
    val coverShape = appBookCoverShape()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(coverShape)
            .then(if(selected && !selecting) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, coverShape) else Modifier)
            .motionClickable(onClick = { if(enabled) onOpen() })) {
            BookCover(book, Modifier.matchParentSize().clearAndSetSemantics {}, showFallbackTitle = false)
            val backdrop = if(eInk) Modifier.background(Color.Black) else Modifier.background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .9f))))
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().then(backdrop)
                .padding(start = if(compact) 6.dp else 10.dp, end = if(compact) 6.dp else 10.dp,
                    top = if(compact) 4.dp else if(eInk) 8.dp else 32.dp, bottom = if(compact) 4.dp else 10.dp),
                verticalArrangement = Arrangement.spacedBy(if(compact) 2.dp else 4.dp)) {
                Text(book.title, color = Color.White, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, maxLines = if(compact || largeText) 2 else 3, overflow = TextOverflow.Ellipsis)
                authorLabel?.let {
                    Text(it, color = Color.White.copy(alpha = .9f), style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(progressLabel ?: status.progressLabel, color = Color.White.copy(alpha = .9f),
                    style = MaterialTheme.typography.labelSmall, maxLines = if(compact || largeText) 1 else 2, overflow = TextOverflow.Ellipsis)
            }
            if(selecting && selected) Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.primary.copy(alpha = .25f)))
            if(selecting) Surface(Modifier.align(Alignment.TopEnd).padding(4.dp), shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .94f)) {
                AppCheckbox(selected, { onToggle() }, enabled = enabled, modifier = Modifier.semantics { contentDescription = "选择${book.title}" })
            } else if(pinned || status.updateLabel != null) Surface(Modifier.align(Alignment.TopStart).padding(6.dp),
                shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .94f)) {
                Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if(pinned) Icon(Icons.Outlined.PushPin, "已置顶", Modifier.size(14.dp))
                    status.updateLabel?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        if(volumeLabel != null && onVolumes != null) AppTextButton(onVolumes, Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .testTag("shelf-grid-volumes-${book.ref.key}").semantics { onClick(label = "打开分卷面板", action = null) }, enabled = !selecting,
            shape = DefaultShapes.small, contentPadding = PaddingValues(horizontal = 6.dp)) {
            Text(volumeLabel, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if(!compact) Icon(Icons.AutoMirrored.Outlined.ViewList, null, Modifier.size(18.dp))
        }
        footer?.invoke()
    }
}
