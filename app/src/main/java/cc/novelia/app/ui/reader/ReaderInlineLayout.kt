package cc.novelia.app.ui.reader

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ReplacementSpan
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import cc.novelia.app.data.model.InlineKind
import cc.novelia.app.reader.TextPart
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

internal class ReaderNoteSpan(val body: String) : UnderlineSpan()

/** 辅文本字号和字距都使用绝对像素，避免缩小字号时连同字距一起缩小。 */
internal class ReaderTextSizeSpan(private val size: Float, private val spacing: Float) : MetricAffectingSpan() {
    override fun updateMeasureState(paint: TextPaint) { paint.textSize = size; paint.letterSpacing = spacing / size }
    override fun updateDrawState(paint: TextPaint) = updateMeasureState(paint)
}

/** 与滚动正文使用同一像素行高；注音行至少保留字形和注音需要的高度。 */
internal class ReaderLineHeightSpan(private val height: Int, private val preserveGlyphHeight: Boolean) : LineHeightSpan {
    private var ascent: Int? = null
    private var descent = 0

    override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, v: Int, fm: Paint.FontMetricsInt) {
        // 字体回退可能令各行的自然度量不同；固定首行基线，才能与 Compose 的居中行高一致。
        val fixedAscent = ascent
        if(!preserveGlyphHeight && fixedAscent != null) {
            fm.ascent = fixedAscent
            fm.descent = descent
            return
        }
        val original = fm.descent - fm.ascent
        if(original <= 0) return
        val target = if(preserveGlyphHeight) max(height, original) else height
        fm.descent += ceil((target - original) / 2.0).toInt()
        fm.ascent = fm.descent - target
        if(!preserveGlyphHeight) {
            ascent = fm.ascent
            descent = fm.descent
        }
    }
}

/** 底文仍占原 UTF-16 区间；一组 Ruby 同行排版，超宽组缩放到视口内。 */
internal class ReaderRubySpan(private val reading: String, private val maxWidth: Int) : ReplacementSpan() {
    private fun rubyPaint(paint: Paint) = Paint(paint).apply { textSize = paint.textSize * .5f; isUnderlineText = false; isStrikeThruText = false }
    private fun width(paint: Paint, text: CharSequence, start: Int, end: Int): Float =
        min(maxWidth.toFloat(), max(paint.measureText(text, start, end), rubyPaint(paint).measureText(reading))).coerceAtLeast(1f)

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) {
            paint.getFontMetricsInt(fm)
            val ruby = rubyPaint(paint).fontMetrics
            val extra = ceil(ruby.descent - ruby.ascent + paint.textSize * .08f).toInt()
            fm.ascent -= extra
            fm.top = min(fm.top, fm.ascent)
        }
        return ceil(width(paint, text, start, end)).toInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val width = width(paint, text, start, end)
        val bodyWidth = paint.measureText(text, start, end).coerceAtLeast(1f)
        canvas.save()
        canvas.translate(x + (width - min(width, bodyWidth)) / 2, y.toFloat())
        canvas.scale(min(1f, width / bodyWidth), 1f)
        canvas.drawText(text, start, end, 0f, 0f, paint)
        canvas.restore()
        val ruby = rubyPaint(paint)
        val rubyWidth = ruby.measureText(reading).coerceAtLeast(1f)
        val baseline = y + paint.fontMetrics.ascent - paint.textSize * .08f - ruby.fontMetrics.descent
        canvas.save()
        canvas.translate(x + (width - min(width, rubyWidth)) / 2, baseline)
        canvas.scale(min(1f, width / rubyWidth), 1f)
        canvas.drawText(reading, 0f, 0f, ruby)
        canvas.restore()
    }
}

internal fun SpannableStringBuilder.addReaderInline(part: TextPart, offset: Int, width: Int) {
    var rubyEnd = -1
    for (mark in part.inline.sortedWith(compareBy({ it.start }, { it.end }))) {
        if (!mark.validFor(part.text)) continue
        val span = when (mark.kind) {
            InlineKind.Bold -> StyleSpan(Typeface.BOLD)
            InlineKind.Italic -> StyleSpan(Typeface.ITALIC)
            InlineKind.Underline -> UnderlineSpan()
            InlineKind.Strike -> StrikethroughSpan()
            InlineKind.Note -> ReaderNoteSpan(mark.value)
            InlineKind.Ruby -> {
                if (mark.start < rubyEnd || '\n' in part.text.substring(mark.start, mark.end)) continue
                rubyEnd = mark.end
                ReaderRubySpan(mark.value, width)
            }
        }
        setSpan(span, offset + mark.start, offset + mark.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

internal fun readerNativeLayout(text: CharSequence, paint: TextPaint, width: Int, lineSpacingPx: Float): StaticLayout {
    val ruby = text is Spanned && text.getSpans(0, text.length, ReaderRubySpan::class.java).isNotEmpty()
    val hasLineHeights = text is Spanned && text.getSpans(0, text.length, ReaderLineHeightSpan::class.java).isNotEmpty()
    val styled = if(hasLineHeights) text else SpannableStringBuilder(text).apply {
        setSpan(ReaderLineHeightSpan(readerLineHeightPx(paint.textSize, lineSpacingPx), ruby),
            0, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
    }
    return StaticLayout.Builder.obtain(styled, 0, styled.length, paint, width.coerceAtLeast(1))
        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
        .setLineSpacing(0f, 1f).build()
}

/** Ruby 在排版上是一个整体；命中其中一个字时仍高亮整组，文字锚点保持原区间。 */
internal fun StaticLayout.readerSelectionPath(start: Int, end: Int, path: android.graphics.Path) {
    var left = start.coerceIn(0, text.length)
    var right = end.coerceIn(left, text.length)
    val spans = text as? Spanned
    if (spans != null && right > left) for (ruby in spans.getSpans(left, right, ReaderRubySpan::class.java)) {
        val rubyStart = spans.getSpanStart(ruby)
        val rubyEnd = spans.getSpanEnd(ruby)
        if (rubyStart < right && rubyEnd > left) { left = minOf(left, rubyStart); right = maxOf(right, rubyEnd) }
    }
    getSelectionPath(left, right, path)
}

/** 点击必须落在注释字形的行和水平范围内，不能把行尾空白吸附到最近的脚注。 */
internal fun StaticLayout.noteAt(x: Float, y: Float): String? {
    if (y < 0 || y >= height || x < 0 || x >= width) return null
    val line = getLineForVertical(y.toInt())
    if (x < getLineLeft(line) || x > getLineRight(line)) return null
    val spans = text as? Spanned ?: return null
    val offset = getOffsetForHorizontal(line, x)
    return spans.getSpans(maxOf(0, offset - 1), minOf(text.length, offset + 1), ReaderNoteSpan::class.java).firstOrNull { span ->
        val start = maxOf(spans.getSpanStart(span), getLineStart(line))
        val end = minOf(spans.getSpanEnd(span), getLineEnd(line))
        if (end <= start) false else {
            val path = android.graphics.Path()
            readerSelectionPath(start, end, path)
            val region = android.graphics.Region()
            region.setPath(path, android.graphics.Region(0, 0, width, height))
            region.contains(x.toInt(), y.toInt())
        }
    }?.body
}
