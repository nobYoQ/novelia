package cc.novelia.app.ui.reader

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Velocity
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.reader.*
import cc.novelia.app.ui.components.media.IllustrationViewer
import cc.novelia.app.ui.theme.AppMotion
import cc.novelia.app.ui.theme.LocalEInkMode
import cc.novelia.app.ui.theme.LocalReducedMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private data class RowMeasureInput(val paragraphs: List<ReadingParagraph>, val title: String,
    val typography: List<Any>, val width: Int, val height: Int, val density: Float, val fontScale: Float)
private data class RowMeasurement(val input: RowMeasureInput, val chapter: MeasuredEInkChapter)

/**
 * 第三种阅读模式：纵向连续移动，落定到完整行；横向从当前行翻一屏。
 * 复用原生文字测量及 PageBody，动画和静止都绘制同一份正文，图片、脚注、注音、
 * 批注、朗读高亮及主题变化不会因缓存旧位图而失效。
 */
@Composable internal fun RowPagedReader(
    paragraphs: List<ReadingParagraph>, settings: ReaderSettings, state: RowPagingState,
    modifier: Modifier = Modifier, imageModel: (ReadingParagraph) -> Any?,
    onToggleMenu: () -> Unit, onSelect: (ReadingParagraph) -> Unit, onPage: (Int) -> Unit,
    background: Color = Color.White, foreground: Color = Color.Black,
    activeMatch: ReadingTextMatch? = null, annotations: List<ReadingTextMatch> = emptyList(),
    highlightLayers: List<ReaderHighlightLayer> = listOfNotNull(activeMatch?.let { ReaderHighlightLayer(it, 1f) }),
    interactionEnabled: Boolean = true, contentPadding: PaddingValues = PaddingValues(0.dp),
    onNavigate: () -> Unit = {}, onNote: (String) -> Unit = {}, chapterTitle: String = "",
    onSavePosition: () -> Unit = {},
    chapterPull: ReaderChapterOverscrollGesture = rememberReaderChapterOverscrollGesture(),
    onNextChapter: (() -> Unit)? = null, chapterEnd: (@Composable () -> Unit)? = null,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val context = LocalContext.current
    val font = rememberReaderFont(settings)
    var expandedImage by remember { mutableStateOf<Any?>(null) }
    val animate = !settings.eInkMode && !LocalEInkMode.current && !LocalReducedMotion.current && ValueAnimator.areAnimatorsEnabled()
    val canInteract = interactionEnabled && expandedImage == null
    val chapterPullOffset = rememberReaderChapterPullOffset(chapterPull, settings.eInkMode || LocalEInkMode.current)
    val scrollDispatcher = remember { NestedScrollDispatcher() }
    val scrollConnection = remember { object : NestedScrollConnection {} }
    SideEffect { if(chapterEnd == null) state.updateEndContentHeight(0) }
    val latestPage by rememberUpdatedState(onPage)
    val latestSave by rememberUpdatedState(onSavePosition)
    val scope = rememberCoroutineScope()
    val flingBehavior = ScrollableDefaults.flingBehavior()
    var gestureJob by remember { mutableStateOf<Job?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var outgoingTop by remember { mutableStateOf<Float?>(null) }
    var incomingOffset by remember { mutableFloatStateOf(0f) }
    var gestureCommitted by remember { mutableStateOf(false) }
    val navigation = rememberReaderTapNavigation(settings.tapPageTurn, canInteract, onToggleMenu,
        { if(gestureJob?.isActive != true) latestPage(it) }, settings.resolvedTapZoneMap, onNavigate)

    BoxWithConstraints(modifier.background(background).clipToBounds()
        .readerTapFeedback(navigation, foreground, settings.eInkMode || LocalEInkMode.current)
        .readerTapNavigation(navigation)
        .readerChapterOverscroll(chapterPull, canInteract && onNextChapter != null,
            atEnd = { state.ready && !state.canScrollForward }, onNextChapter = { onNextChapter?.invoke() })) {
        val horizontalPadding = with(density) {
            contentPadding.calculateLeftPadding(layoutDirection).roundToPx() + contentPadding.calculateRightPadding(layoutDirection).roundToPx()
        }
        val verticalPadding = with(density) {
            contentPadding.calculateTopPadding().roundToPx() + contentPadding.calculateBottomPadding().roundToPx()
        }
        val width = if(constraints.hasBoundedWidth) {
            (minOf(constraints.maxWidth, with(density) { settings.width.dp.roundToPx() }) - horizontalPadding).coerceAtLeast(1)
        } else Constraints.Infinity
        val height = if(constraints.hasBoundedHeight) (constraints.maxHeight - verticalPadding).coerceAtLeast(1) else Constraints.Infinity
        val gap = with(density) { settings.resolvedParagraphSpacing.dp.toPx() }.roundToInt()
        val typography = listOf(settings.fontSize, settings.lineHeight, settings.letterSpacing, settings.paragraphSpacing, settings.weight,
            settings.resolvedIndentSize, settings.parallel, settings.underline, settings.secondaryAlpha, font.typeface, font.loading,
            settings.showEpubRuby, settings.epubFootnotePopup, settings.showEpubStyles)
        val input = RowMeasureInput(paragraphs, chapterTitle, typography, width, height, density.density, density.fontScale)
        val measured by produceState<RowMeasurement?>(null, input) {
            if(value != null) delay(120)
            if(!font.loading && width != Constraints.Infinity && height != Constraints.Infinity) {
                val next = withContext(Dispatchers.Default) {
                    val coroutine = currentCoroutineContext()
                    measureEInkChapter(paragraphs, settings, width, height, density.density, density.fontScale, font.typeface, chapterTitle) {
                        coroutine.ensureActive()
                    }
                }
                state.install(next.pages, next.paragraphs, height, gap)
                value = RowMeasurement(input, next)
            }
        }
        val chapter = measured?.takeIf { it.input == input && state.isMeasured(it.chapter.pages) }?.chapter
        SideEffect { if(chapter == null) state.invalidate() else state.confirm(chapter.pages) }
        LaunchedEffect(chapter, canInteract, animate) {
            gestureJob?.cancel()
            dragOffset = 0f
            outgoingTop = null
            incomingOffset = 0f
            if(state.scrolling) { state.finishScroll(); latestSave() }
        }
        DisposableEffect(state) { onDispose { gestureJob?.cancel(); if(state.scrolling) state.finishScroll() } }
        if(chapter == null) Text("正在分页…", Modifier.align(Alignment.Center), color = foreground)
        else {
            val geometry = state.layout!!
            val showPartialLines = settings.rowShowPartialLines || state.scrolling
            // 渐隐只跟随当前阅读位置；翻页、搜索和重新排版不能带入上一屏的残影。
            val partialLineAlpha = remember(geometry, state.navigationRevision) { Animatable(if(showPartialLines) 1f else 0f) }
            LaunchedEffect(partialLineAlpha, showPartialLines, animate) {
                if(showPartialLines || !animate) partialLineAlpha.snapTo(if(showPartialLines) 1f else 0f)
                else partialLineAlpha.animateTo(0f, tween(AppMotion.Quick))
            }
            val fadingPartialLine by remember(partialLineAlpha, showPartialLines, animate) {
                derivedStateOf { !showPartialLines && animate && partialLineAlpha.value > 0f }
            }
            // 按键、按钮、自动阅读直接更新同一个行游标，也提供横向过渡。
            LaunchedEffect(geometry, state.navigationRevision, canInteract, animate) {
                gestureJob?.cancel()
                dragOffset = 0f
                outgoingTop = null
                incomingOffset = 0f
                val from = state.transitionFrom
                val committed = gestureCommitted
                gestureCommitted = false
                if(animate && canInteract && !committed && from != null && from != state.viewportTop) {
                    val direction = if(state.viewportTop > from) 1f else -1f
                    outgoingTop = from
                    incomingOffset = direction * width
                    try {
                        animate(incomingOffset, 0f, animationSpec = tween(AppMotion.Standard, easing = FastOutSlowInEasing)) { value, _ -> incomingOffset = value }
                    } finally { outgoingTop = null; incomingOffset = 0f }
                }
            }

            fun settleHorizontal(direction: Int) {
                gestureJob?.cancel()
                val target = if(direction == 0) null else state.targetRow(direction)
                if(!animate || (direction != 0 && target == null)) {
                    dragOffset = 0f
                    if(direction != 0) latestPage(direction)
                    return
                }
                gestureJob = scope.launch {
                    try {
                        animate(dragOffset, -direction * width.toFloat(),
                            animationSpec = tween(if(direction == 0) AppMotion.Quick else AppMotion.Standard, easing = FastOutSlowInEasing)) { value, _ -> dragOffset = value }
                        if(direction != 0) { gestureCommitted = true; latestPage(direction) }
                    } finally { dragOffset = 0f }
                }
            }
            val latestSettle by rememberUpdatedState(::settleHorizontal)

            fun settleVertical(velocity: Float = 0f) {
                if(!state.scrolling) return
                gestureJob?.cancel()
                if(!animate) {
                    state.finishScroll()
                    latestSave()
                    return
                }
                gestureJob = scope.launch {
                    // 与连续滚动的 LazyColumn 共用平台惯性，不另设速度倍率或启动门槛。
                    with(flingBehavior) {
                        object : ScrollScope {
                            override fun scrollBy(pixels: Float): Float =
                                if(state.layout === geometry) state.scrollBy(pixels) else 0f
                        }.performFling(velocity)
                    }
                    // 平台惯性可能吞掉取消异常；再次按住时仍须停在当前帧，不能继续吸附。
                    currentCoroutineContext().ensureActive()
                    if(state.layout !== geometry || !state.scrolling) return@launch
                    val from = state.viewportTop
                    val target = state.scrollTarget
                    // 惯性结束后继续沿同一像素游标吸附，首尾局部行始终保留。
                    // 取消时保留当前帧，不能在 finally 中强制跳到整行。
                    if(abs(target - from) > .01f) {
                        animate(from, target, animationSpec = tween(AppMotion.Standard, easing = FastOutSlowInEasing)) { value, _ ->
                            state.settleScroll(value)
                        }
                    }
                    if(state.layout === geometry && state.scrolling) {
                        state.finishScroll()
                        latestSave()
                    }
                }
            }
            val latestVerticalSettle by rememberUpdatedState(::settleVertical)
            val velocity = remember(geometry) { VelocityTracker() }
            Box(Modifier.fillMaxSize().clipToBounds()
                .nestedScroll(scrollConnection, scrollDispatcher)
                .pointerInput(geometry, canInteract) {
                    if(!canInteract) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        velocity.resetTracking()
                        velocity.addPointerInputChange(down)
                        // 再次按住即可停止惯性/吸附，不等待触摸越过拖动阈值。
                        if(state.scrolling) gestureJob?.cancel()
                        while(true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.firstOrNull { it.id == down.id }?.let { velocity.addPointerInputChange(it) }
                            if(event.changes.none { it.pressed }) {
                                // 与列表一样保留历史采样，并在抬手时识别停顿，避免沿用旧速度。
                                awaitPointerEvent(PointerEventPass.Final)
                                break
                            }
                        }
                        if(state.scrolling && gestureJob?.isActive != true) latestVerticalSettle(0f)
                    }
                }
                .pointerInput(geometry, canInteract, animate, settings.horizontalPageTurn) {
                    if(!canInteract) return@pointerInput
                    var axis = -1
                    var total = Offset.Zero
                    detectDragGestures(
                        onDragStart = {
                            gestureJob?.cancel()
                            outgoingTop = null
                            incomingOffset = 0f
                            dragOffset = 0f
                            total = Offset.Zero
                            axis = -1
                        },
                        onDragCancel = {
                            if(axis == 1) latestVerticalSettle(0f)
                            else latestSettle(0)
                        },
                        onDragEnd = end@ {
                            if(!navigation.canSwipe) {
                                if(axis == 1) latestVerticalSettle(0f) else latestSettle(0)
                                return@end
                            }
                            val maximum = viewConfiguration.maximumFlingVelocity
                            val speed = velocity.calculateVelocity(Velocity(maximum, maximum))
                            if(axis == 0 && settings.horizontalPageTurn) {
                                val fling = abs(speed.x) >= 750.dp.toPx() && speed.x * total.x > 0f
                                val passed = abs(total.x) >= 40.dp.toPx() || fling
                                latestSettle(if(passed) if((if(fling) speed.x else total.x) < 0) 1 else -1 else 0)
                            } else if(axis == 1) {
                                latestVerticalSettle(if(chapterPull.suppressFling) 0f else -speed.y)
                            }
                        }
                    ) { change, amount ->
                        if(!navigation.canSwipe) return@detectDragGestures
                        if(axis == -1) {
                            axis = if(abs(amount.x) >= abs(amount.y)) 0 else 1
                            if(axis == 1) state.beginScroll()
                        }
                        if(axis == 0 && !settings.horizontalPageTurn) return@detectDragGestures
                        change.consume()
                        total += amount
                        if(axis == 1) {
                            val available = Offset(0f, amount.y)
                            val preConsumed = scrollDispatcher.dispatchPreScroll(available, NestedScrollSource.UserInput)
                            val remaining = available - preConsumed
                            val consumed = Offset(0f, -state.scrollBy(-remaining.y))
                            scrollDispatcher.dispatchPostScroll(consumed, remaining - consumed, NestedScrollSource.UserInput)
                        }
                        else if(animate) {
                            val direction = if(total.x < 0f) 1 else -1
                            dragOffset = (total.x * if(state.targetRow(direction) == null) .15f else 1f).coerceIn(-width.toFloat(), width.toFloat())
                        }
                    }
                }
                // 点击区域覆盖留白，手势也应从同一视口接收触摸；排版仍使用扣除留白后的尺寸。
                .wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = settings.width.dp).fillMaxWidth()
                .graphicsLayer { translationY = -chapterPullOffset }
                .padding(contentPadding).clipToBounds()) {
                @Composable fun body(top: Float, x: Float) {
                    // 上下滚动始终保留局部行；静止与横翻遵循用户选择，不改变翻页起点。
                    val includePartialLines = showPartialLines || fadingPartialLine
                    val slice = remember(geometry, top, includePartialLines) { geometry.slice(top, includePartialLines) }
                    val completeSlice = remember(geometry, top) { geometry.slice(top, includePartialLines = false) }
                    val firstRow = geometry.rowAt(top)
                    val y = geometry.top(firstRow) - top
                    val partialLineTop = geometry.top(firstRow + completeSlice.lines.size) - geometry.top(firstRow)
                    Box(Modifier.fillMaxSize().graphicsLayer { translationX = x }.background(background).clipToBounds()) {
                        PageBody(slice, chapter, settings, foreground, highlightLayers, annotations, density, context,
                            navigation, { if(canInteract) { navigation.cancel(); onSelect(it) } }, imageModel,
                            { if(canInteract) { navigation.cancel(); expandedImage = it } }, height,
                            { if(canInteract) { navigation.cancel(); onNote(it) } },
                            Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).graphicsLayer { translationY = y }
                                .drawWithContent {
                                    drawContent()
                                    // 在同一份正文上渐隐尾行，完整行、段间距和正文交互不随动画重新排版。
                                    if(fadingPartialLine && partialLineTop < size.height) drawRect(
                                        color = background, topLeft = Offset(0f, partialLineTop),
                                        size = Size(size.width, size.height - partialLineTop), alpha = 1f - partialLineAlpha.value)
                                })
                        if(chapterEnd != null) Box(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true)
                            .onSizeChanged { state.updateEndContentHeight(it.height + gap) }
                            .graphicsLayer { translationY = geometry.totalHeight + gap - top }) { chapterEnd() }
                    }
                }
                val previous = outgoingTop
                if(previous != null && animate) {
                    val direction = if(state.viewportTop > previous) 1 else -1
                    body(previous, incomingOffset - direction * width)
                    body(state.viewportTop, incomingOffset)
                } else {
                    val shift = if(animate) dragOffset else 0f
                    val direction = if(shift < 0) 1 else -1
                    if(shift != 0f) state.targetRow(direction)?.let { body(geometry.top(it), shift + direction * width) }
                    body(state.viewportTop, shift)
                }
            }
        }
    }
    expandedImage?.let { IllustrationViewer(it) { expandedImage = null } }
}
