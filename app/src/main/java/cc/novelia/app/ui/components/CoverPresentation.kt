package cc.novelia.app.ui.components

import androidx.compose.ui.graphics.Color
import cc.novelia.app.data.model.BookCard

internal fun stableCoverVariant(key: String, count: Int): Int = (key.hashCode() and Int.MAX_VALUE) % count

internal enum class WebCoverPalette(val accent: Color) {
    GeneralSerial(Color(0xFF38865A)),
    GeneralCompleted(Color(0xFF8655AE)),
    GeneralShort(Color(0xFF397FB5)),
    AdultSerial(Color(0xFFD978A3)),
    AdultCompleted(Color(0xFFD33F78)),
    AdultShort(Color(0xFFCF7935)),
    Unknown(Color(0xFF78838B)),
}

/** 分类缺失时使用中性色；不从标题、作者或自由关键词猜测分级。 */
internal fun webCoverPalette(book: BookCard): WebCoverPalette {
    if(book.ref.isLocal || book.ref.isWenku || book.attentions == null) return WebCoverPalette.Unknown
    // 原站 R18 筛选同时包含 R18 和性描写；Kakuyomu、Novelup 等书源使用后者。
    val adult = book.attentions.any {
        val attention = it.trim()
        attention == "性描写" || attention.replace("-", "").equals("R18", ignoreCase = true)
    }
    return when(book.novelType?.trim()) {
        "连载中", "连载" -> if(adult) WebCoverPalette.AdultSerial else WebCoverPalette.GeneralSerial
        "已完结", "完结" -> if(adult) WebCoverPalette.AdultCompleted else WebCoverPalette.GeneralCompleted
        "短篇" -> if(adult) WebCoverPalette.AdultShort else WebCoverPalette.GeneralShort
        else -> WebCoverPalette.Unknown
    }
}
