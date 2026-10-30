@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package cc.novelia.app.ui.reader

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.Spannable
import android.text.Spanned
import android.text.style.UnderlineSpan
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import cc.novelia.app.data.model.InlineKind
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.reader.*
import kotlin.math.roundToInt

/** combinedClickable 仍负责滚动取消和长按；这里只记录本次已确认点击的局部坐标。 */
@Composable internal fun Modifier.readerInlineActions(onClick: (Offset?) -> Unit, onLongClick: () -> Unit): Modifier {
    val position = remember { arrayOfNulls<Offset>(1) }
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            position[0] = down.position
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    if (event.changes.none { it.pressed }) break
                }
            } finally { position[0] = null }
        }
    }.combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
        onClickLabel = "查看脚注或显示阅读工具栏", onClick = { onClick(position[0]) },
        onLongClickLabel = "选择段落、分享或添加笔记", onLongClick = onLongClick)
}

@Composable internal fun ReaderInlineText(part: TextPart, settings: ReaderSettings, foreground: Color,
    onToggleMenu: () -> Unit, onSelect: () -> Unit, onNote: (String) -> Unit,
    annotations: List<ReadingTextMatch>, highlights: List<ReaderHighlightLayer>, onLayout: (StaticLayout, Int) -> Unit) {
    val density = LocalDensity.current
    val font = rememberReaderFont(settings)
    var top by remember { mutableIntStateOf(0) }
    val latestLayout by rememberUpdatedState(onLayout)
    BoxWithConstraints(Modifier.fillMaxWidth().onGloballyPositioned { top = it.positionInParent().y.roundToInt() }) {
        val indent = if (settings.indent) "　　" else ""
        val layout = remember(part, settings.fontSize, settings.lineHeight, settings.weight, indent, font.typeface, constraints.maxWidth, density) {
            val text = SpannableStringBuilder(indent + part.text)
            text.addReaderInline(part, indent.length, constraints.maxWidth)
            val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = readerPartFontSize(settings, part.secondary) * density.density * density.fontScale
                typeface = Typeface.create(font.typeface, if (settings.weight) Typeface.BOLD else Typeface.NORMAL)
            }
            readerNativeLayout(text, paint, constraints.maxWidth, settings.lineHeight)
        }
        SideEffect { latestLayout(layout, top) }
        Canvas(Modifier.fillMaxWidth().height(with(density) { layout.height.toDp() })
            .semantics {
                text = AnnotatedString(indent + part.text)
                customActions = part.inline.filter { it.kind == InlineKind.Note }.map { mark ->
                    CustomAccessibilityAction("查看脚注 ${part.text.substring(mark.start, mark.end)}") { onNote(mark.value); true }
                }
            }
            .readerInlineActions({ position ->
                val note = position?.let { layout.noteAt(it.x, it.y) }
                if (note != null) onNote(note) else onToggleMenu()
            }, onSelect)) {
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                fun highlight(start: Int, end: Int, alpha: Float) {
                    val path = android.graphics.Path()
                    layout.readerSelectionPath((start + indent.length).coerceIn(0, layout.text.length),
                        (end + indent.length).coerceIn(0, layout.text.length), path)
                    native.drawPath(path, android.graphics.Paint().apply { color = foreground.copy(alpha = alpha).toArgb() })
                }
                annotations.forEach { highlight(it.start, it.end, .1f) }
                highlights.forEach { highlight(it.match.start, it.match.end, .2f * it.alpha) }
                layout.paint.color = foreground.copy(alpha = if (part.secondary) settings.secondaryAlpha else 1f).toArgb()
                layout.paint.isUnderlineText = settings.underline && part.secondary
                val spannable = layout.text as Spannable
                val annotationSpans = annotations.mapNotNull { match ->
                    val start = (match.start + indent.length).coerceIn(0, layout.text.length)
                    val end = (match.end + indent.length).coerceIn(start, layout.text.length)
                    if (end <= start) null else UnderlineSpan().also { spannable.setSpan(it, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                }
                try { layout.draw(native) } finally { annotationSpans.forEach(spannable::removeSpan) }
            }
        }
    }
}
