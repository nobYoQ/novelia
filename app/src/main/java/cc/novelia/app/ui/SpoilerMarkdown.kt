package cc.novelia.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ReplacementSpan
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.image.AsyncDrawableSpan
import org.commonmark.node.CustomNode
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Text
import org.commonmark.internal.InlineParserImpl
import org.commonmark.internal.util.Escaping
import org.commonmark.parser.InlineParser
import org.commonmark.parser.InlineParserContext
import org.commonmark.parser.Parser
import org.commonmark.parser.delimiter.DelimiterProcessor
import org.commonmark.parser.delimiter.DelimiterRun

internal class SpoilerNode : CustomNode() {
    var renderedSpan: SpoilerSpan? = null
}

/** Parse alongside Markdown, so escaped punctuation and code remain literal. */
internal class SpoilerDelimiterProcessor(private val marker: Char) : DelimiterProcessor {
    override fun getOpeningCharacter() = marker
    override fun getClosingCharacter() = marker
    override fun getMinLength() = 2
    override fun getDelimiterUse(opener: DelimiterRun, closer: DelimiterRun) =
        if (opener.length() >= 2 && closer.length() >= 2) 2 else 0

    override fun process(opener: Text, closer: Text, delimiterUse: Int) {
        val spoiler = SpoilerNode()
        var node = opener.next
        while (node != null && node !== closer) {
            val next = node.next
            spoiler.appendChild(node)
            node = next
        }
        opener.insertAfter(spoiler)
    }
}

/** CommonMark 0.13 reserves '!' for images before consulting delimiter processors. */
internal fun configureSpoilerParser(builder: Parser.Builder) {
    builder.inlineParserFactory { context ->
        InlineParser { input, block ->
            if (!input.contains("!!")) {
                InlineParserImpl(context).parse(input, block)
            } else {
                // Pick punctuation absent from this input, including decoded character entities.
                val decoded = Escaping.unescapeString(input)
                val marker = ('\u2000'..'\u2bff').first { char ->
                    Character.getType(char) == Character.OTHER_PUNCTUATION.toInt() && char !in input && char !in decoded
                }
                val adapted = buildString {
                    var index = 0
                    while (index < input.length) {
                        when {
                            input[index] == '\\' && index + 1 < input.length -> { append(input[index++]); append(input[index++]) }
                            input.startsWith("!!", index) -> { append(marker); append(marker); index += 2 }
                            else -> append(input[index++])
                        }
                    }
                }
                val adaptedContext = object : InlineParserContext by context {
                    override fun getCustomDelimiterProcessors() = context.customDelimiterProcessors + SpoilerDelimiterProcessor(marker)
                }
                InlineParserImpl(adaptedContext).parse(adapted, block)
                // Code and unclosed delimiters stay literal; destinations keep their original URL.
                block.accept(object : AbstractVisitor() {
                    private fun restore(value: String) = value.replace(marker, '!')
                    override fun visit(text: Text) { text.literal = restore(text.literal) }
                    override fun visit(code: Code) { code.literal = restore(code.literal) }
                    override fun visit(html: HtmlInline) { html.literal = restore(html.literal) }
                    override fun visit(link: Link) { link.destination = restore(link.destination); link.title = link.title?.let(::restore); visitChildren(link) }
                    override fun visit(image: Image) { image.destination = restore(image.destination); image.title = image.title?.let(::restore); visitChildren(image) }
                })
            }
        }
    }
}

internal class SpoilerPlugin : AbstractMarkwonPlugin() {
    override fun configureParser(builder: Parser.Builder) {
        configureSpoilerParser(builder)
    }

    override fun configureVisitor(builder: MarkwonVisitor.Builder) {
        builder.on(SpoilerNode::class.java) { visitor, node ->
            val start = visitor.length()
            visitor.visitChildren(node)
            visitor.setSpans(start, node.renderedSpan ?: SpoilerSpan().also { node.renderedSpan = it })
        }
    }
}

internal class SpoilerSpan : ClickableSpan() {
    var revealed = false
        private set

    override fun onClick(widget: View) {
        revealed = !revealed
        val buffer = (widget as? TextView)?.text as? Spannable
        if (buffer != null) {
            // Selectable TextViews cache rendered text. Invalidating the view alone leaves the
            // old black glyphs in that cache; notify its span watcher of the appearance change.
            val changed = if (buffer.getSpanStart(this) >= 0) arrayOf<Any>(this)
                else buffer.getSpans(0, buffer.length, ReplacementSpan::class.java).map { it as Any }.toTypedArray()
            for (span in changed) {
                val start = buffer.getSpanStart(span)
                val end = buffer.getSpanEnd(span)
                val flags = buffer.getSpanFlags(span)
                buffer.removeSpan(span)
                buffer.setSpan(span, start, end, flags)
            }
        }
        widget.invalidate()
        widget.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    // The view paints a mask last, covering nested links, code, images and selection too.
    override fun updateDrawState(ds: TextPaint) {
        ds.color = Color.BLACK
        ds.bgColor = if (revealed) Color.WHITE else Color.BLACK
        ds.isUnderlineText = false
    }
}

internal class SpoilerTextView(context: Context) : TextView(context) {
    // Reading scroll belongs to the surrounding Compose list. TextView otherwise
    // tries to bring its selection into view when an async image calls setText().
    override fun bringPointIntoView(offset: Int): Boolean = false

    private val mask = Paint().apply { color = Color.BLACK }
    private val maskPath = Path()
    var refreshMarkdown: (() -> Unit)? = null
    var documentUrl: String? = null
    var openAnchor: ((String) -> Boolean)? = null
    var openImage: ((String) -> Unit)? = null
    private var pressedImage: AsyncDrawableSpan? = null
    private var imageX = 0f
    private var imageY = 0f
    private var imageDragged = false
    private var imageOpened = false
    private val imageLongPress = Runnable {
        pressedImage?.let {
            imageOpened = true
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            openImage?.invoke(it.drawable.destination)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val buffer = text as? Spanned
                val offset = textOffsetAt(this, event)
                val hidden = buffer?.getSpans(offset.coerceAtLeast(0), offset.coerceAtLeast(0), SpoilerSpan::class.java)
                    ?.any { !it.revealed && offset >= buffer.getSpanStart(it) && offset < buffer.getSpanEnd(it) } == true
                pressedImage = if (offset >= 0 && !hidden && openImage != null) buffer?.getSpans(offset, offset, AsyncDrawableSpan::class.java)
                    ?.firstOrNull { offset >= buffer.getSpanStart(it) && offset < buffer.getSpanEnd(it) } else null
                if (pressedImage != null) {
                    imageX = event.x; imageY = event.y; imageDragged = false; imageOpened = false
                    postDelayed(imageLongPress, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> if (pressedImage != null) {
                val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                if (kotlin.math.abs(event.x - imageX) > slop || kotlin.math.abs(event.y - imageY) > slop) {
                    imageDragged = true
                    removeCallbacks(imageLongPress)
                }
                return true
            }
            MotionEvent.ACTION_UP -> if (pressedImage != null) {
                removeCallbacks(imageLongPress)
                val image = pressedImage!!
                pressedImage = null
                if (!imageOpened && !imageDragged) {
                    // A linked image still follows its link on a short tap.
                    val buffer = text as Spanned
                    buffer.getSpans(buffer.getSpanStart(image), buffer.getSpanEnd(image), ClickableSpan::class.java)
                        .firstOrNull { it !is SpoilerSpan }?.onClick(this)
                    performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { pressedImage = null; removeCallbacks(imageLongPress) }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDetachedFromWindow() {
        removeCallbacks(imageLongPress)
        pressedImage = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val spanned = text as? Spanned ?: return
        val textLayout = layout ?: return
        canvas.save()
        canvas.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())
        spanned.getSpans(0, spanned.length, SpoilerSpan::class.java).filterNot { it.revealed }.forEach {
            maskPath.reset()
            textLayout.getSelectionPath(spanned.getSpanStart(it), spanned.getSpanEnd(it), maskPath)
            canvas.drawPath(maskPath, mask)
        }
        canvas.restore()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        val spanned = text as? Spanned ?: return
        val hidden = spanned.getSpans(0, spanned.length, SpoilerSpan::class.java)
            .filterNot { it.revealed }.sortedByDescending { spanned.getSpanStart(it) }
        if (hidden.isEmpty()) return
        val accessible = SpannableStringBuilder(spanned)
        hidden.forEach { span ->
            val start = accessible.getSpanStart(span)
            val end = accessible.getSpanEnd(span)
            if (start >= 0 && end > start) {
                accessible.getSpans(start, end, ClickableSpan::class.java).forEach(accessible::removeSpan)
                val label = "剧透（点击显示）"
                accessible.replace(start, end, label)
                accessible.setSpan(span, start, start + label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        info.text = accessible
    }
}

internal fun textOffsetAt(widget: TextView, event: MotionEvent): Int {
    val layout = widget.layout ?: return -1
    val x = event.x - widget.totalPaddingLeft + widget.scrollX
    val y = event.y - widget.totalPaddingTop + widget.scrollY
    if (y < 0 || y >= layout.height) return -1
    val line = layout.getLineForVertical(y.toInt())
    if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return -1
    // getOffsetForHorizontal returns the nearest caret. In a replacement span (image), the
    // right half maps to its end; use the preceding glyph when the point is left of that caret.
    val offset = layout.getOffsetForHorizontal(line, x)
    return if (offset > layout.getLineStart(line) && x < layout.getPrimaryHorizontal(offset)) offset - 1 else offset
}

/** A hidden link must reveal its spoiler first, never navigate on that first tap. */
internal class SpoilerMovementMethod : LinkMovementMethod() {
    private var pressed: ClickableSpan? = null
    private var handlingSpoiler = false
    private var downX = 0f
    private var downY = 0f

    override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
        val offset = textOffsetAt(widget, event)
        val spans = if (offset >= 0) buffer.getSpans(offset, offset, ClickableSpan::class.java)
            .filter { offset >= buffer.getSpanStart(it) && offset < buffer.getSpanEnd(it) } else emptyList()
        val hit = spans.filterIsInstance<SpoilerSpan>().firstOrNull { !it.revealed }
            ?: spans.firstOrNull { it !is SpoilerSpan } ?: spans.firstOrNull()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = hit
                handlingSpoiler = hit != null
                downX = event.x
                downY = event.y
                if (hit != null) return true
            }
            MotionEvent.ACTION_MOVE -> if (handlingSpoiler) {
                val slop = android.view.ViewConfiguration.get(widget.context).scaledTouchSlop
                if (kotlin.math.abs(event.x - downX) > slop || kotlin.math.abs(event.y - downY) > slop) pressed = null
                return true
            }
            MotionEvent.ACTION_UP -> {
                val target = pressed
                pressed = null
                if (handlingSpoiler) {
                    handlingSpoiler = false
                    if (target != null && target === hit) target.onClick(widget)
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> { pressed = null; handlingSpoiler = false }
        }
        return super.onTouchEvent(widget, buffer, event)
    }
}
