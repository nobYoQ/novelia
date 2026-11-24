package cc.novelia.app.ui.toolbar

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.model.EdgeToolbarSettings
import cc.novelia.app.ui.components.base.*
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.max
import kotlin.math.roundToInt

/** 在根树观察轻量投影，不因正文进度保存而重组整个导航图。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun EdgeToolbarHost(
    c: AppController,
    route: String?,
    entryId: String?,
    navigationBarHeight: Dp,
    navigationRailWidth: Dp,
    onExpandedChanged: (Boolean) -> Unit,
) {
    val projection = remember(c.store) { c.store.state.map { state ->
        state.edgeToolbar.normalized() to state.downloads.count { it.active }
    }.distinctUntilChanged() }
    val initial = remember(c.store) { c.store.state.value.let {
        it.edgeToolbar.normalized() to it.downloads.count { download -> download.active }
    } }
    val state by projection.collectAsStateWithLifecycle(initialValue = initial)
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val reader = LocalEdgeToolbarReaderState.current?.entries?.get(entryId)
    val reading = route?.startsWith("reader/") == true
    val readerVisible = !reading || (reader != null && !reader.blocked && when(state.first.readerVisibility) {
        "hidden" -> false
        "always" -> true
        else -> reader.menu
    })
    val visible = state.first.enabled && edgeToolbarRouteAllowed(route) && readerVisible &&
        LocalAppOverlayRegistry.current?.count.let { it == null || it == 0 } &&
        WindowInsets.ime.getBottom(density) == 0 && lifecycle == Lifecycle.State.RESUMED && LocalWindowInfo.current.isWindowFocused
    val safeTop = WindowInsets.safeDrawing.getTop(density)
    val left = WindowInsets.safeDrawing.getLeft(density, direction) + with(density) { navigationRailWidth.roundToPx() }
    val right = WindowInsets.safeDrawing.getRight(density, direction)
    val top = if(reading) max(safeTop, reader?.top ?: 0) else safeTop + with(density) { 64.dp.roundToPx() }
    // 阅读状态栏显隐、底部导航与阅读菜单的高度都不能重新定位圆点。
    val stableInsets = WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
    val anchorInsets = EdgeToolbarInsets(left = stableInsets.getLeft(density, direction),
        top = stableInsets.getTop(density), right = stableInsets.getRight(density, direction))
    // 根容器已消费系统底部 inset；这里只排除应用自己的导航与阅读控件。
    val bottom = with(density) { navigationBarHeight.roundToPx() } + if(reading) reader?.bottom ?: 0 else 0
    EdgeToolbar(state.first, visible, state.second, entryId, left, right, top, bottom,
        anchorInsets = anchorInsets,
        onPositionChange = { position -> c.store.update { it.copy(edgeToolbar = it.edgeToolbar.normalized().copy(position = position)) } },
        onNavigate = { destination ->
            if(route?.substringBefore('?') != destination) c.go(destination, replaceTop = true)
        }, onExpandedChanged = onExpandedChanged)
}

/** 布局与手势独立于业务页面，面板和圆点始终是覆盖层，不改变正文视口。 */
@Composable internal fun EdgeToolbar(
    settings: EdgeToolbarSettings,
    visible: Boolean,
    downloadCount: Int = 0,
    pageKey: Any? = null,
    leftInset: Int = 0,
    rightInset: Int = 0,
    topInset: Int = 0,
    bottomInset: Int = 0,
    anchorInsets: EdgeToolbarInsets = EdgeToolbarInsets(),
    onPositionChange: (Float) -> Unit,
    onNavigate: (String) -> Unit,
    onExpandedChanged: (Boolean) -> Unit = {},
) {
    val preferences = settings.normalized()
    val density = LocalDensity.current
    val eInk = LocalEInkMode.current
    val border = monochromeBorder()
    val flat = eInk || border != null
    val reduced = appReducedMotion()
    var size by remember { mutableStateOf(IntSize.Zero) }
    var expanded by remember(pageKey) { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(preferences.position) }
    var dragging by remember { mutableStateOf(false) }
    var dragOrigin by remember { mutableFloatStateOf(fraction) }
    var idle by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val touch = with(density) { 48.dp.roundToPx() }
    val gap = with(density) { 12.dp.roundToPx() }
    val bounds = EdgeToolbarBounds(leftInset, topInset + gap,
        (size.width - rightInset).coerceAtLeast(leftInset), (size.height - bottomInset - gap).coerceAtLeast(topInset + gap))
    val anchor = EdgeToolbarBounds(anchorInsets.left, anchorInsets.top + gap,
        (size.width - anchorInsets.right).coerceAtLeast(anchorInsets.left),
        (size.height - anchorInsets.bottom - gap).coerceAtLeast(anchorInsets.top + gap))
    val shown = visible && anchor.width >= touch * 2 && anchor.height >= touch
    val open = shown && expanded
    val dotVisibility = edgeToolbarVisibility(shown, reduced)
    val panelVisibility = edgeToolbarVisibility(open, reduced)
    val renderDot by remember(shown, dotVisibility) { derivedStateOf { shown || dotVisibility.value > 0f } }
    val renderPanel by remember(open, panelVisibility) { derivedStateOf { open || panelVisibility.value > 0f } }
    // 离页时用原来的面板边界退场，避免先跳到新页面的避让位置再消失。
    val panelAnchor = bounds to anchor.centerY(fraction, touch)
    var lastOpenPanelAnchor by remember { mutableStateOf(panelAnchor) }
    SideEffect { if(open) lastOpenPanelAnchor = panelAnchor }
    val (panelBounds, panelCenterY) = if(open) panelAnchor else lastOpenPanelAnchor
    val savePosition by rememberUpdatedState(onPositionChange)
    fun wake() { idle = false; interaction++ }
    fun close() { expanded = false; wake() }
    fun navigate(target: String) { close(); onNavigate(target) }
    LaunchedEffect(preferences.position) { if(!dragging) fraction = preferences.position }
    LaunchedEffect(shown) {
        if(!shown) { expanded = false; focused = false }
    }
    // 导航和临时隐藏不会唤醒圆点，也不会重新开始倒计时；只有工具栏交互才唤醒。
    LaunchedEffect(open, dragging, pressed, focused, interaction, preferences.idleSeconds) {
        if(open || dragging || pressed || focused || preferences.idleSeconds == 0) idle = false
        else if(!idle) {
            delay(preferences.idleSeconds * 1_000L)
            idle = true
        }
    }
    SideEffect { onExpandedChanged(open) }
    DisposableEffect(Unit) { onDispose { onExpandedChanged(false) } }
    BackHandler(open) { close() }
    val retreatTarget = if(idle && !open && !dragging) 1f else 0f
    val animatedRetreat by animateFloatAsState(retreatTarget,
        tween(if(reduced) 0 else AppMotion.Standard), label = "edge dot retreat")
    val retreat = if(reduced) retreatTarget else animatedRetreat
    val dotAlpha = if(eInk) 1f else 1f - retreat * (1f - preferences.idleOpacity)
    val rightSide = preferences.side == "right"
    val shift = with(density) { 24.dp.toPx() } * retreat * if(rightSide) 1 else -1
    val circleBackground = if(eInk) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary
    val circleForeground = if(eInk) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onPrimary
    val panelShape = appPanelShape(RoundedCornerShape(20.dp))
    val navigateUpdated by rememberUpdatedState<(String) -> Unit>({ navigate(it) })
    Layout(modifier = Modifier.fillMaxSize().clipToBounds().onSizeChanged { size = it }
        .testTag("edge-toolbar-layer"), content = {
        if(renderPanel) {
            Box(Modifier.fillMaxSize().background(if(eInk) Color.Transparent else MaterialTheme.colorScheme.scrim.copy(alpha = .10f))
                .clickable(enabled = open, interactionSource = remember { MutableInteractionSource() }, indication = null) { close() }
                .clearAndSetSemantics { }.testTag("edge-toolbar-scrim"))
            Surface(shape = panelShape, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = if(flat) 0.dp else 6.dp, border = border,
                modifier = Modifier.testTag("edge-toolbar-panel")
                    .then(if(open) Modifier.semantics { paneTitle = "快捷入口"; isTraversalGroup = true }
                        else Modifier.clearAndSetSemantics { })) {
                Column(Modifier.appVerticalScroll(rememberScrollState(), enabled = open).padding(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("快捷入口", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleSmall)
                        AppIconButton(onClick = { navigate("edge-toolbar-edit") }, enabled = open) { Icon(Icons.Outlined.Edit, "编辑快捷入口") }
                        AppIconButton(onClick = { close() }, enabled = open) { Icon(Icons.Outlined.Close, "收起快捷入口") }
                    }
                    preferences.entries.forEach { id ->
                        val entry = edgeShortcuts.first { it.id == id }
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = open) { navigate(entry.id) }.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(entry.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            Text(entry.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            if(id == "downloads" && preferences.downloadBadge && downloadCount > 0) {
                                Text(if(downloadCount > 99) "99+" else downloadCount.toString(),
                                    Modifier.semantics { contentDescription = "$downloadCount 个进行中的下载任务" },
                                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    AppTextButton(onClick = { navigate("edge-toolbar") }, enabled = open, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Outlined.Tune, null, Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)); Text("工具栏设置")
                    }
                }
            }
        }
        if(renderDot) {
            Box(Modifier.size(48.dp).clipToBounds().testTag("edge-toolbar-dot")
                .then(if(shown) Modifier else Modifier.clearAndSetSemantics { })
                .onFocusChanged { focused = it.isFocused }
                .pointerInput(pageKey, anchor, preferences.side, shown, eInk) {
                    if(!shown) return@pointerInput
                    // 电子纸只累计目标高度；松手一次定位，不在移动事件中触发绘制。
                    var dragFraction = fraction
                    try {
                        detectVerticalDragGestures(
                            onDragStart = { dragOrigin = fraction; dragFraction = fraction; dragging = true; wake() },
                            onDragEnd = { fraction = dragFraction; dragging = false; savePosition(fraction); wake() },
                            onDragCancel = { fraction = dragOrigin; dragging = false; wake() },
                            onVerticalDrag = { change, amount ->
                                change.consume()
                                dragFraction = anchor.fractionAt(anchor.centerY(dragFraction, touch) + amount, touch)
                                if(!eInk) fraction = dragFraction
                            },
                        )
                    } finally { if(dragging) { fraction = dragOrigin; dragging = false } }
                }
                .clickable(enabled = shown, interactionSource = interactionSource, indication = null, role = Role.Button,
                    onClickLabel = if(open) "收起快捷入口" else "打开快捷入口") { wake(); expanded = !expanded }
                .semantics {
                    contentDescription = "快捷工具栏"
                    stateDescription = if(open) "已展开" else if(idle) "半隐藏，可上下拖动" else "可上下拖动"
                    customActions = listOf(
                        CustomAccessibilityAction("向上移动") { wake(); fraction = (fraction - .1f).coerceAtLeast(0f); onPositionChange(fraction); true },
                        CustomAccessibilityAction("向下移动") { wake(); fraction = (fraction + .1f).coerceAtMost(1f); onPositionChange(fraction); true },
                        CustomAccessibilityAction("工具栏设置") { navigateUpdated("edge-toolbar"); true },
                    )
                }, contentAlignment = Alignment.Center) {
                // 圆点是固定形状的拖动把手，直角模式也保留圆形；只移动视觉层，触控区留在屏幕内。
                Surface(shape = CircleShape, color = circleBackground, contentColor = circleForeground,
                    shadowElevation = if(flat) 0.dp else 2.dp,
                    modifier = Modifier.size(32.dp).graphicsLayer { translationX = shift; alpha = dotAlpha }) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Widgets, null, Modifier.size(18.dp)) }
                }
            }
        }
    }) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val dot = if(renderDot) measurables.last().measure(Constraints.fixed(touch, touch)) else null
        val scrim = if(renderPanel) measurables[0].measure(Constraints.fixed(width, height)) else null
        val panelWidth = minOf(248.dp.roundToPx(), (panelBounds.width - 68.dp.roundToPx()).coerceAtLeast(1))
        val panel = if(renderPanel) measurables[1].measure(Constraints(minWidth = panelWidth, maxWidth = panelWidth, maxHeight = panelBounds.height)) else null
        val centerY = anchor.centerY(fraction, touch)
        layout(width, height) {
            scrim?.placeWithLayer(0, 0) { alpha = panelVisibility.value }
            panel?.let {
                val x = if(rightSide) panelBounds.right - 56.dp.roundToPx() - it.width else panelBounds.left + 56.dp.roundToPx()
                val y = panelBounds.panelTop(panelCenterY, it.height)
                it.placeWithLayer(x.coerceIn(panelBounds.left, (panelBounds.right - it.width).coerceAtLeast(panelBounds.left)), y) {
                    alpha = panelVisibility.value
                    scaleX = .96f + .04f * panelVisibility.value
                    scaleY = scaleX
                    transformOrigin = TransformOrigin(if(rightSide) 1f else 0f,
                        ((panelCenterY - y) / it.height.coerceAtLeast(1)).coerceIn(0f, 1f))
                }
            }
            dot?.placeWithLayer(if(rightSide) anchor.right - touch else anchor.left, (centerY - touch / 2f).roundToInt()) {
                alpha = dotVisibility.value
            }
        }
    }
}

/** 保留退场内容；反向切换从当前进度继续，静态模式在当帧直接采用目标值。 */
@Composable private fun edgeToolbarVisibility(visible: Boolean, reduced: Boolean): State<Float> {
    val progress = remember { Animatable(0f) }
    val target = if(visible) 1f else 0f
    LaunchedEffect(visible, reduced) {
        if(reduced) progress.snapTo(target)
        else progress.animateTo(target, tween(if(visible) AppMotion.Standard else AppMotion.Quick))
    }
    return remember(visible, reduced) { derivedStateOf { if(reduced) target else progress.value } }
}
