package cc.novelia.app.sharing

import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.drawable.toBitmap
import cc.novelia.app.R
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlin.math.min

private const val WIDTH = 1080
private const val HEIGHT = 1350
private const val LEFT = 76
private const val RIGHT = WIDTH - LEFT
private const val COLUMN = RIGHT - LEFT
private val ink = Color.rgb(48, 55, 46)
private val green = Color.rgb(62, 107, 59)
private val muted = Color.rgb(89, 99, 82)
private val light = Color.rgb(220, 232, 212)
private val rule = Color.rgb(198, 205, 188)
private val bold = Typeface.create("sans-serif-medium", Typeface.NORMAL)

private fun layout(text: CharSequence, size: Int, width: Int, color: Int, font: Typeface, gap: Int, max: Int): StaticLayout {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size.toFloat(); this.color = color; typeface = font }
    val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
        .setIncludePad(false).setLineSpacing(gap.toFloat(), 1f).setAlignment(Layout.Alignment.ALIGN_NORMAL)
    if (max != Int.MAX_VALUE) builder.setMaxLines(max).setEllipsize(TextUtils.TruncateAt.END)
    return builder.build()
}

private fun Canvas.text(text: CharSequence, x: Int, y: Int, size: Int, width: Int = RIGHT - x,
    color: Int = ink, font: Typeface = Typeface.DEFAULT, max: Int = 1, gap: Int = 0, maxHeight: Int = Int.MAX_VALUE): Int {
    if (text.isBlank()) return 0
    var block = layout(text, size, width, color, font, gap, max)
    while (block.height > maxHeight && block.lineCount > 1) {
        block = layout(text, size, width, color, font, gap, block.lineCount - 1)
    }
    if (block.height > maxHeight) return 0
    save()
    translate(x.toFloat(), y.toFloat())
    block.draw(this)
    restore()
    return block.height
}

private fun Canvas.rect(x: Int, y: Int, w: Int, h: Int, color: Int, radius: Int = 0) {
    drawRoundRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), radius.toFloat(), radius.toFloat(),
        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
}

private fun Canvas.photo(bitmap: Bitmap, x: Int, y: Int, w: Int, h: Int, radius: Int) {
    val dst = RectF(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat())
    val scale = maxOf(w.toFloat() / bitmap.width, h.toFloat() / bitmap.height)
    val matrix = Matrix().apply {
        setScale(scale, scale)
        postTranslate(dst.centerX() - bitmap.width * scale / 2, dst.centerY() - bitmap.height * scale / 2)
    }
    val bitmapShader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
    drawRoundRect(dst, radius.toFloat(), radius.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { shader = bitmapShader })
}

internal class CardArtwork(private val card: ShareCard, private val sticker: Bitmap, private val cover: Bitmap?,
    private val postExcerpt: CharSequence?) {
    private val qr = card.url?.let(::cardQr)
    private val quote = if (card.kind == CardKind.Quote)
        layout(card.body, 58, COLUMN, ink, Typeface.SERIF, 18, Int.MAX_VALUE) else null
    // 以实际排版行分页，原文换行、代理对与双向文字都交给 StaticLayout。
    private val pageStarts = buildList {
        add(0)
        val block = quote ?: return@buildList
        var start = 0
        for (line in 1 until block.lineCount) {
            if (block.getLineBottom(line) - block.getLineTop(start) > 620) { add(line); start = line }
        }
    }
    val pageCount get() = pageStarts.size

    fun render(page: Int, showQr: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paper = card.kind == CardKind.Quote
        with(canvas) {
            drawColor(if (paper) Color.rgb(244, 236, 216) else Color.rgb(241, 244, 236))
            rect(LEFT, 84, 7, 43, if (paper) muted else green, 3)
            text(card.heading, LEFT + 27, 77, 36, color = if (paper) muted else green, font = bold)
            when (card.kind) {
                CardKind.Post -> {
                    val h = text(card.title, LEFT, 207, 80, font = bold, max = 3, gap = 7)
                    val authorY = 207 + h + 34
                    text(card.byline, LEFT, authorY, 35, color = muted)
                    rect(LEFT, authorY + 83, COLUMN, 2, rule)
                    val excerptY = authorY + 125
                    text(requireNotNull(postExcerpt), LEFT, excerptY, 42, max = 6, gap = 12, maxHeight = 982 - excerptY)
                    tags(card.tags, LEFT, 1014)
                }
                CardKind.Book -> {
                    if (cover != null) photo(cover, LEFT, 209, 330, 464, 24) else {
                        rect(LEFT, 209, 330, 464, light, 24)
                        photo(sticker, LEFT + 27, 263, 275, 275, 0)
                        text(card.title, LEFT + 28, 550, 32, 275, font = bold, max = 2)
                    }
                    val h = text(card.title, 454, 243, 68, font = bold, max = 3, gap = 4)
                    text(card.byline, 456, 265 + h, 35, color = muted, max = 2)
                    tags(card.tags, 455, 617)
                    rect(LEFT, 746, COLUMN, 2, rule)
                    if (card.body.isNotBlank()) {
                        text("内容简介", LEFT, 790, 35, color = green, font = bold)
                        text(card.body, LEFT, 864, 41, max = 4, gap = 5)
                    }
                }
                CardKind.Quote -> {
                    val block = requireNotNull(quote)
                    text("“", LEFT, 154, 140, color = Color.rgb(158, 146, 112), font = Typeface.SERIF)
                    val start = block.getLineTop(pageStarts[page])
                    val end = pageStarts.getOrNull(page + 1)?.let(block::getLineTop) ?: block.height
                    save()
                    clipRect(LEFT, 277, RIGHT, 277 + end - start)
                    translate(LEFT.toFloat(), 277f - start)
                    block.draw(this)
                    restore()
                    rect(LEFT, 924, 64, 4, muted)
                    text("《${card.title}》", LEFT, 949, 39, font = bold)
                    text(card.byline, LEFT, 1017, 33, color = muted)
                    if (pageCount > 1) text("${page + 1} / $pageCount", 804, 83, 30, color = muted)
                }
            }
            rect(LEFT, 1104, COLUMN, 2, rule)
            photo(sticker, LEFT, 1170, 94, 94, 17)
            text("Novelia", 187, 1182, 53, font = bold)
            if (showQr && qr != null) {
                val unit = (218 / qr.width).coerceAtLeast(1)
                val side = qr.width * unit
                val x = RIGHT - side
                val y = 1220 - side / 2
                rect(x, y, side, side, Color.WHITE)
                val black = Paint().apply { color = Color.BLACK }
                for (row in 0 until qr.height) for (col in 0 until qr.width) if (qr[col, row]) {
                    val l = (x + col * unit).toFloat()
                    val t = (y + row * unit).toFloat()
                    drawRect(l,t,l+unit,t+unit,black)
                }
                text(card.linkLabel, x - 184, 1197, 29, 175, muted)
            }
        }
        return bitmap
    }

    private fun Canvas.tags(tags: List<String>, left: Int, y: Int) {
        var x = left
        val paint = Paint().apply { textSize = 29f }
        for (tag in tags.filter(String::isNotBlank).take(2)) {
            val w = min(paint.measureText(tag).toInt() + 46, RIGHT - x)
            if (w < 70) break
            rect(x, y, w, 55, light, 13)
            text(tag, x + 22, y + 7, 29, w - 39, muted)
            x += w + 14
        }
    }
}

internal suspend fun loadArtwork(context: Context, card: ShareCard): CardArtwork {
    val sticker = BitmapFactory.decodeResource(context.resources, R.drawable.midori_reading, BitmapFactory.Options().apply { inScaled = false })
    val image = card.cover?.let { source ->
        val result = context.imageLoader.execute(ImageRequest.Builder(context).data(source).size(420, 590).allowHardware(false).build())
        (result as? SuccessResult)?.drawable?.toBitmap()
    }
    val excerpt = if (card.kind == CardKind.Post) renderPostExcerpt(context, card.body, green) else null
    return CardArtwork(card, sticker, image, excerpt)
}
