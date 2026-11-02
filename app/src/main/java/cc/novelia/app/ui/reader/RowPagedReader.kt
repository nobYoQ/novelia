package cc.novelia.app.ui.reader

import android.animation.ValueAnimator
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
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
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val font = rememberReaderFont(settings)
    var expandedImage by remember { mutableStateOf<Any?>(null) }
    val animate = !settings.eInkMode && !LocalEInkMode.current && !LocalReducedMotion.current && ValueAnimator.areAnimatorsEnabled()
    val canInteract = interactionEnabled && expandedImage == null
    val latestPage by rememberUpdatedState(onPage)
    val latestSave by rememberUpdatedState(onSavePosition)
    val scope = rememberCoroutineScope()
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
        .wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = settings.width.dp).fillMaxWidth()
        .padding(contentPadding).clipToBounds()) {
        val width = constraints.maxWidth.coerceAtLeast(1)
        val height = constraints.maxHeight.coerceAtLeast(1)
        val gap = with(density) { settings.resolvedParagraphSpacing.dp.toPx() }.roundToInt()
        val typography = listOf(settings.fontSize, settings.lineHeight, settings.paragraphSpacing, settings.weight,
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
            Box(Modifier.fillMaxSize().clipToBounds()
                .pointerInput(geometry, canInteract, animate, settings.horizontalPageTurn) {
                    if(!canInteract) return@pointerInput
                    var axis = -1
                    var total = Offset.Zero
                    val velocity = VelocityTracker()
                    detectDragGestures(
                        onDragStart = {
                            gestureJob?.cancel()
                            outgoingTop = null
                            incomingOffset = 0f
                            dragOffset = 0f
                            total = Offset.Zero
                            axis = -1
                            velocity.resetTracking()
                        },
                        onDragCancel = {
                            if(axis == 1) { state.finishScroll(); latestSave() }
                            else latestSettle(0)
                        },
                        onDragEnd = end@ {
                            if(!navigation.canSwipe) {
                                if(axis == 1) { state.finishScroll(); latestSave() } else latestSettle(0)
                                return@end
                            }
                            val speed = velocity.calculateVelocity()
                            if(axis == 0 && settings.horizontalPageTurn) {
                                val fling = abs(speed.x) >= 750.dp.toPx() && speed.x * total.x > 0f
                                val passed = abs(total.x) >= 40.dp.toPx() || fling
                                latestSettle(if(passed) if((if(fling) speed.x else total.x) < 0) 1 else -1 else 0)
                            } else if(axis == 1) {
                                gestureJob = scope.launch {
                                    try {
                                        if(animate && abs(speed.y) >= 150.dp.toPx()) {
                                            var previous = 0f
                                            AnimationState(0f, -speed.y).animateDecay(exponentialDecay()) {
                                                val delta = value - previous
                                                previous = value
                                                if(abs(state.scrollBy(delta) - delta) > .5f) cancelAnimation()
                                            }
                                        }
                                    } finally {
                                        if(state.layout === geometry && state.scrolling) { state.finishScroll(); latestSave() }
                                    }
                                }
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
                        velocity.addPosition(change.uptimeMillis, change.position)
                        if(axis == 1) state.scrollBy(-amount.y)
                        else if(animate) {
                            val direction = if(total.x < 0f) 1 else -1
                            dragOffset = (total.x * if(state.targetRow(direction) == null) .15f else 1f).coerceIn(-width.toFloat(), width.toFloat())
                        }
                    }
                }) {
                @Composable fun body(top: Float, x: Float, scrolling: Boolean = false) {
                    val slice = remember(geometry, top, scrolling) { geometry.slice(top, scrolling) }
                    val y = if(scrolling) geometry.top(geometry.rowAt(top)) - top else 0f
                    Box(Modifier.fillMaxSize().graphicsLayer { translationX = x }.background(background).clipToBounds()) {
                        PageBody(slice, chapter, settings, foreground, highlightLayers, annotations, density, context,
                            navigation, { if(canInteract) { navigation.cancel(); onSelect(it) } }, imageModel,
                            { if(canInteract) { navigation.cancel(); expandedImage = it } }, height,
                            { if(canInteract) { navigation.cancel(); onNote(it) } },
                            Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).graphicsLayer { translationY = y })
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
                    body(state.viewportTop, shift, state.scrolling)
                }
            }
        }
    }
    expandedImage?.let { IllustrationViewer(it) { expandedImage = null } }
}
