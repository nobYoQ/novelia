@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package cc.novelia.app.ui.reader

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import cc.novelia.app.reader.ReaderTapAction
import cc.novelia.app.reader.ReaderTapKind
import cc.novelia.app.reader.ReaderTapZoneMap
import cc.novelia.app.reader.ReaderTapRect
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 只观察正文视口中的指针，不抢占列表、长按或按钮的事件。
 * 真正的点击仍由现有 clickable / combinedClickable 确认，再按视口坐标分区。
 * 键盘与无障碍点击没有指针坐标，始终保留工具栏入口。
 *
 * 点击分区方案来自 [ReaderTapZoneMap]（移植自 FBReaderJ）；
 * 仅有双击动作的区域才等待确认；子控件仍通过现有 clickable 确认点击，
 * 长按、滚动、取消、设置变化和离开页面都会清理待确认动作。
 */
internal class ReaderTapNavigation(private val scope: CoroutineScope) {
    private var tapPageTurn = false
    private var pageTurningEnabled = true
    private var onToggleMenu: () -> Unit = {}
    private var onPage: (Int) -> Unit = {}
    private var onNavigate: () -> Unit = {}
    private var zoneMap: ReaderTapZoneMap = ReaderTapZoneMap.Default
    private var tracking = false
    private var valid = false
    private var tap = false
    private var released = false
    private var start = Offset.Zero
    private var width = 0
    private var height = 0
    private var feedbackId = 0L
    private var downTime = 0L
    private var upTime = 0L
    private var secondTap = false
    private var doubleTapTimeout = 300L
    private var doubleTapMinTime = 40L
    private var doubleTapSlop = 48f
    private data class Tap(val position: Offset, val cell: Int, val upTime: Long)
    private var pending: Tap? = null
    private var pendingJob: Job? = null
    var feedback by mutableStateOf<ReaderTapFeedback?>(null)
        private set

    val canSwipe: Boolean get() = pageTurningEnabled && tracking && valid

    fun configure(tapPageTurn: Boolean, pageTurningEnabled: Boolean, onToggleMenu: () -> Unit, onPage: (Int) -> Unit,
        zoneMap: ReaderTapZoneMap = ReaderTapZoneMap.Default, onNavigate: () -> Unit = {},
        doubleTapTimeout: Long = 300L, doubleTapMinTime: Long = 40L, doubleTapSlop: Float = 48f) {
        if(this.tapPageTurn != tapPageTurn || this.pageTurningEnabled != pageTurningEnabled || this.zoneMap != zoneMap) {
            cancel()
            feedback = null
        }
        this.tapPageTurn = tapPageTurn
        this.pageTurningEnabled = pageTurningEnabled
        this.onToggleMenu = onToggleMenu
        this.onPage = onPage
        this.zoneMap = zoneMap
        this.onNavigate = onNavigate
        this.doubleTapTimeout = doubleTapTimeout
        this.doubleTapMinTime = doubleTapMinTime
        this.doubleTapSlop = doubleTapSlop
    }

    fun begin(position: Offset, width: Int, height: Int = 0, timeMillis: Long = 0L) {
        val previous = pending
        secondTap = previous != null && this.width == width && this.height == height &&
            timeMillis - previous.upTime in doubleTapMinTime..doubleTapTimeout &&
            (position - previous.position).getDistance() <= doubleTapSlop &&
            zoneMap.cellAt(position.x, position.y, width, height)?.let { it in zoneMap.regionFor(previous.cell).cells } == true
        if(secondTap) pendingJob?.cancel() else completePending()
        feedback = null
        tracking = true
        valid = true
        tap = true
        released = false
        start = position
        this.width = width
        this.height = height
        downTime = timeMillis
    }

    private fun clearPending() { pendingJob?.cancel(); pendingJob = null; pending = null }
    private fun completePending() {
        val previous = pending ?: return
        clearPending()
        dispatch(previous.cell, ReaderTapKind.SingleNotDoubleTap)
    }
    fun cancel() { valid = false; clearPending() }
    fun dispose() { cancel(); tracking = false }
    fun moved(position: Offset, slop: Float, insideViewport: Boolean) {
        // 越过阈值后即使回到起点也不能重新成为点击。
        // 越出正文只取消点击；已开始的滑动仍可在视口外松手提交。
        if(!insideViewport || (position - start).getDistance() > slop) { tap = false; clearPending() }
    }
    fun release(shortPress: Boolean, timeMillis: Long = downTime) { released = true; tap = tap && shortPress; upTime = timeMillis }
    fun finish() { if(!tap || !released) clearPending(); tracking = false; valid = false }
    fun clearFeedback(value: ReaderTapFeedback) { if(feedback == value) feedback = null }

    fun click() {
        if(!tracking) { clearPending(); onToggleMenu(); return }
        if(!valid || !tap || !released) return
        valid = false
        // 搜索等状态暂停翻页时，仍能通过正文收起或展开工具栏。
        if(!pageTurningEnabled || !tapPageTurn || width <= 0 || height <= 0) {
            clearPending()
            feedback = null
            onToggleMenu()
            return
        }
        val cell = zoneMap.cellAt(start.x, start.y, width, height) ?: return
        if(secondTap && pending != null) {
            clearPending()
            dispatch(cell, ReaderTapKind.DoubleTap)
        } else if(zoneMap.doubleAt(cell % zoneMap.columns, cell / zoneMap.columns) != ReaderTapAction.None) {
            pending = Tap(start, cell, upTime)
            pendingJob = scope.launch { delay(doubleTapTimeout); completePending() }
        } else dispatch(cell, ReaderTapKind.SingleNotDoubleTap)
    }

    private fun dispatch(cell: Int, kind: ReaderTapKind) {
        if(!pageTurningEnabled || !tapPageTurn) return
        val action = zoneMap.getActionByZone(cell % zoneMap.columns, cell / zoneMap.columns, kind)
        // 只反馈已确认的区域单击；滑动、长按、控件及无障碍点击不会产生闪烁。
        feedback = when(action) {
            ReaderTapAction.PreviousPage, ReaderTapAction.NextPage -> ReaderTapFeedback(++feedbackId, zoneMap.regionFor(cell).bounds)
            else -> null
        }
        when(action) {
            ReaderTapAction.PreviousPage -> onPage(-1)
            ReaderTapAction.NextPage -> onPage(1)
            ReaderTapAction.Navigate -> onNavigate()
            ReaderTapAction.Menu -> onToggleMenu()
            ReaderTapAction.None -> Unit
        }
    }
}

internal data class ReaderTapFeedback(val id: Long, val bounds: List<ReaderTapRect>)

@Composable internal fun rememberReaderTapNavigation(
    tapPageTurn: Boolean, pageTurningEnabled: Boolean = true, onToggleMenu: () -> Unit, onPage: (Int) -> Unit,
    zoneMap: ReaderTapZoneMap = ReaderTapZoneMap.Default, onNavigate: () -> Unit = {}
): ReaderTapNavigation {
    val scope = rememberCoroutineScope()
    val navigation = remember(scope) { ReaderTapNavigation(scope) }
    val config = LocalViewConfiguration.current
    val doubleTapSlop = with(LocalDensity.current) { 48.dp.toPx() }
    SideEffect { navigation.configure(tapPageTurn, pageTurningEnabled, onToggleMenu, onPage, zoneMap, onNavigate,
        config.doubleTapTimeoutMillis, config.doubleTapMinTimeMillis, doubleTapSlop) }
    DisposableEffect(navigation) { onDispose { navigation.dispose() } }
    return navigation
}

@Composable internal fun Modifier.readerTapNavigation(navigation: ReaderTapNavigation): Modifier =
    pointerInput(navigation) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val viewport = size
            navigation.begin(down.position, viewport.width, viewport.height, down.uptimeMillis)
            if(down.isConsumed || currentEvent.changes.size != 1) navigation.cancel()
            try {
                while(true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pointer = event.changes.firstOrNull { it.id == down.id }
                    if(pointer == null || pointer.isConsumed || size != viewport ||
                        event.changes.any { it.id != down.id && (it.pressed || it.previousPressed) }) navigation.cancel()
                    if(pointer != null) {
                        navigation.moved(pointer.position, viewConfiguration.touchSlop,
                            pointer.position.x in 0f..viewport.width.toFloat() && pointer.position.y in 0f..viewport.height.toFloat())
                    }
                    if(event.changes.none { it.pressed }) {
                        navigation.release(pointer != null && pointer.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis,
                            pointer?.uptimeMillis ?: down.uptimeMillis)
                        // 子项在 Main 阶段确认点击；Final 后清理，避免语义点击复用旧触点。
                        awaitPointerEvent(PointerEventPass.Final)
                        break
                    }
                }
            } finally { navigation.finish() }
        }
    }.combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
        onClickLabel = "显示或收起阅读工具栏", onClick = navigation::click)
