package cc.novelia.app.ui.markdown

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.Layout
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.ReplacementSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.view.View
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.core.CoreProps
import io.noties.markwon.image.ImageProps
import java.util.WeakHashMap
import kotlin.math.cos
import kotlin.math.sin
import org.commonmark.node.Heading
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.SoftLineBreak
import org.commonmark.parser.Parser

internal class SiteMarkdownPlugin(context: Context) : AbstractMarkwonPlugin() {
    private val density = context.resources.displayMetrics.density
    // Each image occurrence owns its drawable for the lifetime of its parsed document.
    // Rebuilding a fold must not reset images elsewhere to their unloaded alt text.
    private val imageSpans = WeakHashMap<Image, Any>()
    override fun configureParser(builder: Parser.Builder) = configureSiteMarkdownParser(builder)
    override fun beforeRender(node: Node) = prepareSiteMarkdown(node)
    override fun configureVisitor(builder: MarkwonVisitor.Builder) {
        builder.on(Heading::class.java) { visitor, node ->
            visitor.blockStart(node)
            val start = visitor.length()
            visitor.visitChildren(node)
            CoreProps.HEADING_LEVEL.set(visitor.renderProps(), node.level)
            visitor.setSpansForNodeOptional(node, start)
            visitor.setSpans(start, HeadingAnchorSpan(node))
            visitor.blockEnd(node)
        }
        builder.on(Image::class.java) { visitor, node ->
            val start = visitor.length()
            visitor.visitChildren(node)
            val config = visitor.configuration()
            val factory = config.spansFactory().get(Image::class.java)
            if (factory != null) {
                if (start == visitor.length()) visitor.builder().append('\uFFFC')
                val span = imageSpans[node] ?: run {
                    val props = visitor.renderProps()
                    ImageProps.DESTINATION.set(props, config.imageDestinationProcessor().process(node.destination))
                    ImageProps.REPLACEMENT_TEXT_IS_LINK.set(props, node.parent is Link)
                    ImageProps.IMAGE_SIZE.set(props, null)
                    factory.getSpans(config, props)?.also { imageSpans[node] = it }
                }
                visitor.setSpans(start, span)
            }
        }
        builder.on(SoftLineBreak::class.java) { visitor, _ -> visitor.forceNewLine() }
        builder.on(StrikeNode::class.java) { visitor, node ->
            val start = visitor.length()
            visitor.visitChildren(node)
            visitor.setSpans(start, StrikethroughSpan())
        }
        builder.on(RatingNode::class.java) { visitor, node ->
            visitor.blockStart(node)
            val start = visitor.length()
            val label = "${node.value.toString().removeSuffix(".0")} / 5 星"
            visitor.builder().append(label)
            visitor.setSpans(start, RatingSpan(node.value, density))
            visitor.blockEnd(node)
        }
        builder.on(DetailsNode::class.java) { visitor, node ->
            visitor.blockStart(node)
            val start = visitor.length()
            visitor.builder().append(if (node.expanded) "▼ " else "▶ ").append(node.title)
            visitor.setSpans(start, DetailsToggleSpan(node))
            visitor.setSpans(start, StyleSpan(Typeface.BOLD))
            visitor.setSpans(start, MinimumLineHeightSpan((48 * density).toInt()))
            if (node.expanded && node.firstChild != null) {
                visitor.forceNewLine()
                visitor.visitChildren(node)
            }
            visitor.setSpans(start, DetailsBorderSpan(density))
            visitor.blockEnd(node)
        }
    }
}

private class MinimumLineHeightSpan(private val height: Int) : LineHeightSpan {
    override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
        val extra = (height - (fm.descent - fm.ascent)).coerceAtLeast(0)
        fm.ascent -= extra / 2
        fm.descent += extra - extra / 2
        fm.top = minOf(fm.top, fm.ascent)
        fm.bottom = maxOf(fm.bottom, fm.descent)
    }
}

internal class DetailsToggleSpan(val node: DetailsNode) : ClickableSpan() {
    override fun onClick(widget: View) {
        node.expanded = !node.expanded
        (widget as? SpoilerTextView)?.refreshMarkdown?.invoke()
    }
    override fun updateDrawState(ds: TextPaint) { ds.isUnderlineText = false }
}

private class DetailsBorderSpan(private val density: Float) : LeadingMarginSpan {
    override fun getLeadingMargin(first: Boolean) = (16 * density).toInt()
    override fun drawLeadingMargin(c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout) {
        val color = p.color
        p.color = Color.GRAY
        c.drawRect(x.toFloat(), top.toFloat(), x + dir * density, bottom.toFloat(), p)
        p.color = color
    }
}

internal class RatingSpan(val value: Double, density: Float) : ReplacementSpan() {
    private val size = 24 * density
    private val gap = 8 * density
    private val padding = 6 * density
    private val star = Path().apply {
        repeat(10) { point ->
            val angle = Math.PI * point / 5 - Math.PI / 2
            val radius = size * if (point % 2 == 0) .5f else .22f
            val x = size / 2 + (cos(angle) * radius).toFloat()
            val y = size / 2 + (sin(angle) * radius).toFloat()
            if (point == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        fm?.apply { ascent = -(size + padding).toInt(); descent = padding.toInt(); top = ascent; bottom = descent }
        return (size * 5 + gap * 4).toInt()
    }
    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.save()
        canvas.translate(x, y - size)
        repeat(5) { index ->
            starPaint.color = Color.rgb(204, 207, 212)
            canvas.drawPath(star, starPaint)
            canvas.save()
            canvas.clipRect(0f, 0f, size * (value - index).coerceIn(0.0, 1.0).toFloat(), size)
            starPaint.color = Color.rgb(79, 178, 51)
            canvas.drawPath(star, starPaint)
            canvas.restore()
            canvas.translate(size + gap, 0f)
        }
        canvas.restore()
    }
}
