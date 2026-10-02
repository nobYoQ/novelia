package cc.novelia.app.data.catalog

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/** 展示概数保留一位小数；筛选边界和计算继续使用原始 Long 字数。 */
fun formatApproximateCharacters(value: Long): String {
    val units = listOf(1L to "", 1_000L to "千", 10_000L to "万", 100_000_000L to "亿")
    var index = units.indexOfLast { value >= it.first }.coerceAtLeast(0)
    if(index == 0) return "$value 字"
    fun rounded() = BigDecimal.valueOf(value).divide(BigDecimal.valueOf(units[index].first), 1, RoundingMode.HALF_UP)
    var number = rounded()
    if(index < units.lastIndex && number.multiply(BigDecimal.valueOf(units[index].first)) >= BigDecimal.valueOf(units[index + 1].first)) {
        index++
        number = rounded()
    }
    return "${number.stripTrailingZeros().toPlainString()} ${units[index].second}字"
}

fun formatExactCharacters(value: Long): String = "${NumberFormat.getIntegerInstance(Locale.CHINA).format(value)} 字"
