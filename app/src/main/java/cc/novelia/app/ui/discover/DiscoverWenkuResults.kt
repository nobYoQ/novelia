package cc.novelia.app.ui.discover

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.BookCard
import cc.novelia.app.ui.components.base.AppLazyColumn
import cc.novelia.app.ui.components.book.BookCover
import cc.novelia.app.ui.components.book.BookFavoriteIndicator
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.LocalEInkMode
import cc.novelia.app.ui.theme.appReducedMotion
import cc.novelia.app.ui.theme.appBookCoverShape
import cc.novelia.app.ui.theme.motionClickable

/** 用懒加载的封面行复用应用的电子纸翻屏与搜索收起手势。 */
@Composable internal fun DiscoverWenkuResults(
    books: List<BookCard>,
    grid: Boolean,
    onBookClick: (BookCard) -> Unit,
    modifier: Modifier = Modifier,
    keywordLabels: Map<String, String> = emptyMap(),
    onPageTurn: (Int) -> Unit = {},
    emptyContent: @Composable () -> Unit = {},
    footer: @Composable () -> Unit = {},
    listScroll: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    gridScroll: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
) {
    val reducedMotion = appReducedMotion()
    val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 1.3f)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val gridColumns = ((maxWidth - 28.dp) / (172.dp * fontScale)).toInt().coerceAtLeast(2)
        val columns = if(grid) gridColumns else 1
        var previousColumns by rememberSaveable { mutableIntStateOf(columns) }
        // 两种布局的行高不同，按最上方的作品定位；不能直接复用行号和像素偏移。
        // requestScrollToItem 在下一次测量前生效，避免过渡首帧闪回列表顶部。
        SideEffect {
            if(previousColumns != columns) {
                val source = if(previousColumns == 1) listScroll else gridScroll
                val target = if(grid) gridScroll else listScroll
                val firstBook = (source.firstVisibleItemIndex * previousColumns).coerceAtMost(books.size)
                val targetRow = if(firstBook == books.size) (books.size + columns - 1) / columns else firstBook / columns
                target.requestScrollToItem(targetRow)
                previousColumns = columns
            }
        }
        val content: @Composable (Boolean) -> Unit = { showGrid ->
            val rows = remember(books, gridColumns) { books.chunked(gridColumns) }
            AppLazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = if(showGrid) gridScroll else listScroll,
                listModifier = Modifier.testTag(if(showGrid) "discover-wenku-grid" else "discover-wenku-list"),
                contentPadding = PaddingValues(top = if(showGrid) 8.dp else 0.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(if(showGrid) 14.dp else 0.dp),
                onPageTurn = onPageTurn,
            ) {
                if(books.isEmpty()) item(key = "empty", contentType = "empty") { emptyContent() }
                if(showGrid) items(rows, key = { it.first().ref.key }, contentType = { "cover-row" }) { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { book ->
                            key(book.ref.key) { DiscoverWenkuCover(book, { onBookClick(book) }, Modifier.weight(1f)) }
                        }
                        repeat(gridColumns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                } else items(books, key = { it.ref.key }, contentType = { "book" }) { book ->
                    DiscoverBookRow(book, { onBookClick(book) }, keywordLabels = keywordLabels)
                }
                item(key = "pages", contentType = "controls") { footer() }
            }
        }
        if(reducedMotion) content(grid)
        else AnimatedContent(
            targetState = grid,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                ((fadeIn(tween(AppMotion.Panel, delayMillis = 40)) +
                    scaleIn(tween(AppMotion.Panel, easing = FastOutSlowInEasing), initialScale = .96f)) togetherWith
                    (fadeOut(tween(AppMotion.Exit)) + scaleOut(tween(AppMotion.Exit), targetScale = .98f)))
                    .using(SizeTransform(clip = false))
            },
            label = "wenku layout",
        ) { content(it) }
    }
}

@Composable private fun DiscoverWenkuCover(book: BookCard, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val eInk = LocalEInkMode.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(appBookCoverShape())
            .motionClickable(onClick = onClick).semantics(mergeDescendants = true) { role = Role.Button }
            .testTag("discover-wenku-cover-${book.ref.key}")) {
            BookCover(book, Modifier.matchParentSize().clearAndSetSemantics {}, showFallbackTitle = false)
            val backdrop = if(eInk) Modifier.background(Color.Black) else Modifier.background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .88f))))
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().then(backdrop)
                .padding(start = 10.dp, end = 10.dp, top = if(eInk) 8.dp else 36.dp, bottom = 12.dp)) {
                Text(book.title, color = Color.White, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        BookFavoriteIndicator(book)
    }
}
