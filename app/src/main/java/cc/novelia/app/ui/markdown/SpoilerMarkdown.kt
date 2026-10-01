package cc.novelia.app.ui.markdown

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
import org.commonmark.internal.InlineParserImpl
import org.commonmark.internal.util.Escaping
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.CustomNode
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Text
import org.commonmark.parser.InlineParser
import org.commonmark.parser.InlineParserContext
import org.commonmark.parser.Parser
import org.commonmark.parser.delimiter.DelimiterProcessor
import org.commonmark.parser.delimiter.DelimiterRun

internal class SpoilerNode : CustomNode() {
    var renderedSpan: SpoilerSpan? = null
}

/** 与 Markdown 一同解析，转义标点和代码仍按字面显示。 */
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

/** CommonMark 0.13 在调用分隔符处理器前，先将 '!' 保留给图片语法。 */
internal fun configureSpoilerParser(builder: Parser.Builder) {
    builder.inlineParserFactory { context ->
        InlineParser { input, block ->
            if (!input.contains("!!")) {
                InlineParserImpl(context).parse(input, block)
            } else {
                // 选择输入中未出现的标点，也检查解码后的字符实体。
                val decoded = Escaping.unescapeString(input)
                val marker = ('\u2000'..'\u2bff').firstOrNull { char ->
                    Character.getType(char) == Character.OTHER_PUNCTUATION.toInt() && char !in input && char !in decoded
                }
                // 不可信文本可能占满所有候选分隔符；此时按普通 Markdown 显示，
                // 避免因找不到备用分隔符而崩溃。
                if (marker == null) {
                    InlineParserImpl(context).parse(input, block)
                    return@InlineParser
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
                // 代码和未闭合分隔符按字面保留，链接目标继续使用原始 URL。
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
            // 可选中文字的 TextView 会缓存渲染结果，仅使视图失效仍会留下
            // 旧的黑色字形；需通知 span 监听器外观已经改变。
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

    // 最后绘制遮罩，同时覆盖嵌套链接、代码、图片和选区。
    override fun updateDrawState(ds: TextPaint) {
        ds.color = Color.BLACK
        ds.bgColor = if (revealed) Color.WHITE else Color.BLACK
        ds.isUnderlineText = false
    }
}

internal class SpoilerTextView(context: Context) : TextView(context) {
    // 阅读滚动由外围 Compose 列表负责；否则异步图片调用 setText() 时，
    // TextView 会尝试滚动以显露自身选区。
    override fun bringPointIntoView(offset: Int): Boolean = false

    private val mask = Paint().apply { color = Color.BLACK }
    private val revealedBackground = Paint().apply { color = Color.WHITE }
    private val maskPath = Path()
    private val revealedPath = Path()
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
                    // 带链接的图片在短按时仍按链接跳转。
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

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        val spanned = text as? Spanned ?: return
        val textLayout = layout ?: return
        canvas.save()
        canvas.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())
        val spoilers = spanned.getSpans(0, spanned.length, SpoilerSpan::class.java)
        // 原生焦点高亮在 onDraw 之后绘制，可能把已揭示的白色背景染灰；
        // 整个 View 绘制结束后仅重绘剧透区间，保留周围的键盘焦点反馈，
        // 并合并裁切区，使布局只需重绘一次。
        revealedPath.reset()
        spoilers.filter { it.revealed }.forEach {
            maskPath.reset()
            textLayout.getSelectionPath(spanned.getSpanStart(it), spanned.getSpanEnd(it), maskPath)
            revealedPath.addPath(maskPath)
        }
        if(!revealedPath.isEmpty) {
            canvas.save()
            canvas.clipPath(revealedPath)
            canvas.drawPaint(revealedBackground)
            textLayout.draw(canvas)
            canvas.restore()
        }
        spoilers.filterNot { it.revealed }.forEach {
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
    // getOffsetForHorizontal 返回最近的光标位置；图片替换 span 的右半边
    // 会映射到末尾，触点在该光标左侧时应使用前一个字形位置。
    val offset = layout.getOffsetForHorizontal(line, x)
    return if (offset > layout.getLineStart(line) && x < layout.getPrimaryHorizontal(offset)) offset - 1 else offset
}

/** 隐藏链接首次点击只揭示剧透，不能同时触发跳转。 */
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
