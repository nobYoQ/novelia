@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui.reader

import cc.novelia.app.ui.components.base.AppTextButton
import cc.novelia.app.ui.components.base.AppOutlinedButton
import cc.novelia.app.ui.components.base.AppIconButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.reader.*
import cc.novelia.app.ui.feedback.MidoriSticker
import cc.novelia.app.ui.feedback.StickerAccent
import cc.novelia.app.ui.theme.AppMotion


internal data class ReaderTopToolbarState(
    val title: String,
    val localBook: Boolean,
    val leaving: Boolean,
    val translationUpdated: Boolean,
    val showReturnPoint: Boolean,
    val returnEnabled: Boolean,
    val speechStatus: String,
)

internal data class ReaderChapterSearchState(
    val visible: Boolean,
    val query: String,
    val finding: Boolean,
    val matchIndex: Int,
    val matchCount: Int,
)

/** 工具栏只展示页面投影并发出事件；搜索任务、导航和保存仍由阅读会话持有。 */
@Composable internal fun ReaderTopToolbar(
    state: ReaderTopToolbarState, search: ReaderChapterSearchState,
    menu: Boolean, reducedMotion: Boolean, toolbarBackground: Color, foreground: Color,
    modifier: Modifier, onHeightChanged: (Int) -> Unit,
    onBack: () -> Unit, onRefresh: () -> Unit, onToggleSearch: () -> Unit,
    onSpeech: () -> Unit, onAutoRead: () -> Unit, onReturnToReading: () -> Unit,
    onQueryChange: (String) -> Unit, onFind: (Int) -> Unit, onBookSearch: () -> Unit,
) {
    ReaderOverlayVisibility(menu, modifier, enter = if(reducedMotion) EnterTransition.None else fadeIn(tween(AppMotion.Quick)) + slideInVertically(tween(AppMotion.Standard)) { -it }, exit = if(reducedMotion) ExitTransition.None else fadeOut(tween(AppMotion.Exit)) + slideOutVertically(tween(AppMotion.Release)) { -it }) {
        Surface(Modifier.testTag("reader-top-toolbar"), color = toolbarBackground, contentColor = foreground) {
        // 此高度仅用于把搜索结果放在浮层下方。
        Column(Modifier.onSizeChanged { onHeightChanged(it.height) }) {
            TopAppBar(title = { Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) }, navigationIcon = {
                AppIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
            }, actions = {
                if(!state.localBook) AppIconButton(onClick = onRefresh, enabled = !state.leaving) { Icon(Icons.Outlined.Refresh, "刷新本章译文") }
                AppIconButton(onClick = onToggleSearch) { Icon(Icons.Outlined.Search, "搜索本章") }
                AppIconButton(onClick = onAutoRead) { Icon(Icons.Outlined.PlayArrow, "自动阅读") }
                AppIconButton(onClick = onSpeech) {
                    if(state.speechStatus == ReadAloudService.SLEEP_TIMER_FINISHED) StickerAccent(MidoriSticker.Sleep, state.speechStatus, Modifier.size(40.dp).semantics { contentDescription = "朗读定时已结束，打开朗读设置" })
                    else Icon(Icons.Outlined.VolumeUp, "朗读本章")
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent, titleContentColor = foreground, actionIconContentColor = foreground, navigationIconContentColor = foreground))
            if(state.translationUpdated) AppTextButton(onClick = onRefresh, enabled = !state.leaving, colors = ButtonDefaults.textButtonColors(contentColor = foreground)) { Text("本书有新的译文，可刷新本章") }
            if(state.showReturnPoint) AppTextButton(onClick = onReturnToReading, enabled = state.returnEnabled,
                colors = ButtonDefaults.textButtonColors(contentColor = foreground), modifier = Modifier.testTag("reader-return-to-reading")) {
                Icon(Icons.Outlined.Undo, null, Modifier.size(18.dp)); Text("回到刚才阅读处", Modifier.padding(start = 6.dp))
            }
            AnimatedVisibility(search.visible,
                enter = if(reducedMotion) EnterTransition.None else fadeIn(tween(AppMotion.Quick)) + expandVertically(tween(AppMotion.Standard), expandFrom = Alignment.Top),
                exit = if(reducedMotion) ExitTransition.None else fadeOut(tween(AppMotion.Exit)) + shrinkVertically(tween(AppMotion.Release), shrinkTowards = Alignment.Top)
            ) {
                Column {
                Row(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(search.query, onQueryChange, label = { Text("搜索本章段落") }, singleLine = true, modifier = Modifier.weight(1f), enabled = search.visible,
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = foreground, unfocusedTextColor = foreground,
                            focusedBorderColor = foreground, unfocusedBorderColor = foreground.copy(alpha = .5f),
                            focusedLabelColor = foreground, unfocusedLabelColor = foreground.copy(alpha = .7f), cursorColor = foreground))
                    AppTextButton(onClick = { onFind(1) }, enabled = search.visible && search.query.isNotBlank() && !search.finding,
                        colors = ButtonDefaults.textButtonColors(contentColor = foreground, disabledContentColor = foreground.copy(alpha = .38f))) { Text(if(search.finding) "查找中" else "查找") }
                }
                if(search.matchCount > 0) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${(search.matchIndex + 1).coerceAtLeast(0)} / ${search.matchCount}${if(search.matchCount == 2000) "+" else ""}", style = MaterialTheme.typography.labelMedium, color = foreground)
                    AppTextButton(onClick = { onFind(-1) }, enabled = !search.finding, colors = ButtonDefaults.textButtonColors(contentColor = foreground)) { Text("上一处") }
                    AppTextButton(onClick = { onFind(1) }, enabled = !search.finding, colors = ButtonDefaults.textButtonColors(contentColor = foreground)) { Text("下一处") }
                }
                AppTextButton(onClick = onBookSearch, Modifier.padding(horizontal = 12.dp), colors = ButtonDefaults.textButtonColors(contentColor = foreground)) { Text("整本搜索（本地 / 已缓存章节）") }
                }
            }
        }
        }
    }
}

internal data class ReaderBottomToolbarState(
    val previousPageEnabled: Boolean,
    val nextPageEnabled: Boolean,
    val seekEnabled: Boolean,
    val previousChapterEnabled: Boolean,
    val nextChapterEnabled: Boolean,
    val nextIsVolume: Boolean,
    val bookmarkEnabled: Boolean,
    val bookmarked: Boolean,
    val progress: Float,
    val pageReady: Boolean,
    val pageCount: Int,
    val pageIndex: Int,
    val chapterTitle: String,
    val wide: Boolean,
    val cached: Boolean,
)

@Composable internal fun ReaderBottomToolbar(
    state: ReaderBottomToolbarState, settings: ReaderSettings, menu: Boolean, reducedMotion: Boolean,
    toolbarBackground: Color, foreground: Color, readingStatusBarHeight: androidx.compose.ui.unit.Dp, statusColumns: Int,
    modifier: Modifier, onHeightChanged: (Int) -> Unit,
    onPage: (Int) -> Unit, onSeek: (Float) -> Unit, onSeekFocusChanged: (Boolean) -> Unit,
    onPreviousChapter: () -> Unit, onNextChapter: () -> Unit, onToc: () -> Unit,
    onBookmark: () -> Unit, onPreferences: () -> Unit,
) {
    Column(modifier.testTag("reader-bottom-toolbar").onSizeChanged { onHeightChanged(it.height) }) {
        val persistentControls = settings.showPageButtons || settings.visibleReadingStatusItems.isNotEmpty()
        if(settings.showPageButtons) Row(Modifier.fillMaxWidth().background(toolbarBackground).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            AppOutlinedButton(onClick = { onPage(-1) }, colors = ButtonDefaults.outlinedButtonColors(contentColor = foreground, disabledContentColor = foreground.copy(alpha = .38f)), enabled = state.previousPageEnabled) { Text(if(settings.staticPagination) "上一页" else "上一屏") }
            AppOutlinedButton(onClick = { onPage(1) }, colors = ButtonDefaults.outlinedButtonColors(contentColor = foreground, disabledContentColor = foreground.copy(alpha = .38f)), enabled = state.nextPageEnabled) { Text(if(settings.staticPagination) "下一页" else "下一屏") }
        }
        ReaderOverlayVisibility(menu, Modifier, enter = if(reducedMotion) EnterTransition.None else fadeIn(tween(AppMotion.Quick)) + slideInVertically(tween(AppMotion.Standard)) { it }, exit = if(reducedMotion) ExitTransition.None else fadeOut(tween(AppMotion.Exit)) + slideOutVertically(tween(AppMotion.Release)) { it }) {
            Surface(color = toolbarBackground, contentColor = foreground) {
                Column(if(persistentControls) Modifier else Modifier.navigationBarsPadding()) {
                    if(settings.showProgressBar) ReaderSeekBar(state.progress,
                        pageCount = if(settings.staticPagination && state.pageReady) state.pageCount else null,
                        enabled = state.seekEnabled,
                        foreground = foreground, onSeek = onSeek, modifier = Modifier.padding(top = 8.dp).onFocusChanged { onSeekFocusChanged(it.hasFocus) })
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        AppIconButton(onClick = onPreviousChapter, enabled = state.previousChapterEnabled) { Icon(Icons.Outlined.SkipPrevious, "上一章") }
                        AppTextButton(onClick = onToc, colors = ButtonDefaults.textButtonColors(contentColor = foreground)) { Icon(Icons.Outlined.FormatListBulleted, null, Modifier.size(18.dp)); Text(if(state.wide && settings.showWideToc) " 收起目录" else " 目录") }
                        AppIconButton(onClick = onBookmark, enabled = state.bookmarkEnabled, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                            Icon(if(state.bookmarked) Icons.Outlined.BookmarkAdded else Icons.Outlined.BookmarkAdd, if(state.bookmarked) "已保存书签" else "保存书签")
                        }
                        AppIconButton(onClick = onPreferences) { Icon(Icons.Outlined.TextFields, "阅读设置") }
                        AppIconButton(onClick = onNextChapter, enabled = state.nextChapterEnabled) { Icon(Icons.Outlined.SkipNext, if(state.nextIsVolume) "下一分卷" else "下一章") }
                    }
                    val toolbarHint = if(settings.tapPageTurn) "点击中间区域收起工具栏" else "点击正文收起工具栏"
                    Text(if(settings.staticPagination) "${if(settings.eInkMode) "电子纸" else "分页阅读"} · $toolbarHint" else "${if(state.cached) "本地内容 · " else ""}$toolbarHint", Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp), style = MaterialTheme.typography.labelSmall, color = foreground)
                }
            }
        }
        if(settings.visibleReadingStatusItems.isNotEmpty()) ReaderReadingStatusBar(state, settings, statusColumns, foreground,
            Modifier.fillMaxWidth().background(toolbarBackground)
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal))
                .height(readingStatusBarHeight))
        if(persistentControls) Spacer(Modifier.fillMaxWidth().background(toolbarBackground).navigationBarsPadding())
    }
}

// 浮层显隐独立于外层自适应 Row 中会影响尺寸的扩展函数。
@Composable private fun ReaderOverlayVisibility(
    visible: Boolean,
    modifier: Modifier,
    enter: EnterTransition,
    exit: ExitTransition,
    content: @Composable androidx.compose.animation.AnimatedVisibilityScope.() -> Unit
) { AnimatedVisibility(visible, modifier, enter, exit, content = content) }
