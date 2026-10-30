@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package cc.novelia.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.novelia.app.data.model.ReaderSettings
import cc.novelia.app.reader.*
import kotlin.math.roundToInt


@Composable internal fun ReaderTextParagraph(paragraph: ReadingParagraph, settings: ReaderSettings, layoutGeneration: Any, foreground: Color, onToggleMenu: () -> Unit, onSelect: () -> Unit, activeMatch: ReadingTextMatch?,
    fontFamily: FontFamily = rememberReaderFont(settings).family,
    annotations: List<ReadingTextMatch> = emptyList(),
    highlightLayers: List<ReaderHighlightLayer> = listOfNotNull(activeMatch?.let { ReaderHighlightLayer(it, 1f) }),
    onNote: (String) -> Unit = {},
    onLayout: (Int, List<ReadingAnchorLine>) -> Unit) {
    val starts = remember(paragraph, settings.indent, settings.parallel) { paragraphPartStarts(paragraph, settings) }
    val latestLayout by rememberUpdatedState(onLayout)
    Column(Modifier.fillMaxWidth().combinedClickable(
        interactionSource = remember { MutableInteractionSource() }, indication = null,
        onClickLabel = "显示或收起阅读工具栏", onClick = onToggleMenu,
        onLongClickLabel = "选择段落、分享或添加笔记", onLongClick = onSelect
    ), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        paragraph.parts.forEachIndexed { partIndex, part ->
            val measurement = remember(layoutGeneration, paragraph) { ScrollPartMeasurement() }
            fun reportLayout() {
                val layout = measurement.layout ?: return
                val top = measurement.top ?: return
                if(measurement.reportedLayout === layout && measurement.reportedTop == top) return
                measurement.reportedLayout = layout
                measurement.reportedTop = top
                latestLayout(partIndex, (0 until layout.lineCount).map { line ->
                    ReadingAnchorLine(starts[partIndex] + layout.getLineStart(line), starts[partIndex] + layout.getLineEnd(line), top + layout.getLineTop(line).roundToInt())
                })
            }
            if(settings.parallel && (part.source == "sakura" || part.source == "gpt" || part.source == "youdao")) Text(part.source.uppercase(), style = MaterialTheme.typography.labelSmall, color = foreground.copy(alpha = .65f))
            val styledPart = remember(part, settings.showEpubRuby, settings.epubFootnotePopup, settings.showEpubStyles) { part.withInlinePreferences(settings) }
            if (styledPart.inline.isNotEmpty()) {
                ReaderInlineText(styledPart, settings, foreground, onToggleMenu, onSelect, onNote,
                    annotations.filter { it.part == partIndex }, highlightLayers.filter { it.match.part == partIndex }) { layout, top ->
                    latestLayout(partIndex, (0 until layout.lineCount).map { line ->
                        ReadingAnchorLine(starts[partIndex] + layout.getLineStart(line), starts[partIndex] + layout.getLineEnd(line), top + layout.getLineTop(line))
                    })
                }
                return@forEachIndexed
            }
            val highlighted = buildAnnotatedString {
                val indent = if(settings.indent) "　　" else ""
                append(indent); append(part.text)
                annotations.filter { it.part == partIndex }.forEach {
                    val start = (indent.length + it.start).coerceIn(0, length)
                    val end = (indent.length + it.end).coerceIn(start, length)
                    addStyle(SpanStyle(textDecoration = TextDecoration.Underline, background = foreground.copy(alpha = .1f)), start, end)
                }
                highlightLayers.filter { it.match.part == partIndex && it.match.end > it.match.start }.forEach { layer ->
                    val it = layer.match
                    val start = (indent.length + it.start).coerceIn(0, length)
                    val end = (indent.length + it.end).coerceIn(start, length)
                    addStyle(SpanStyle(background = foreground.copy(alpha = .2f * layer.alpha)), start, end)
                }
            }
            Text(highlighted,
                modifier = Modifier.onGloballyPositioned { measurement.top = it.positionInParent().y.roundToInt(); reportLayout() },
                onTextLayout = { measurement.layout = it; reportLayout() },
                fontSize = readerPartFontSize(settings, part.secondary).sp,
                lineHeight = readerPartLineHeight(settings, part.secondary).sp,
                fontWeight = if(settings.weight) FontWeight.Bold else FontWeight.Normal,
                fontFamily = fontFamily,
                color = foreground.copy(alpha = if(part.secondary) settings.secondaryAlpha else 1f),
                textDecoration = if(settings.underline && part.secondary) TextDecoration.Underline else null)
        }
    }
}

private class ScrollPartMeasurement {
    var layout: TextLayoutResult? = null
    var top: Int? = null
    var reportedLayout: TextLayoutResult? = null
    var reportedTop: Int? = null
}
