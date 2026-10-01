package cc.novelia.app.reader

import cc.novelia.app.data.model.ReaderSettings
import kotlin.math.roundToLong
import kotlin.math.roundToInt

/** paragraph 为投影列表下标；文字段落的 textOffset 为包含标签、分隔符和缩进的 UTF-16 偏移。 */
internal data class ChapterSeekAnchor(val paragraph: Int, val textOffset: Int)

/**
 * 按显示字符数加权的章节索引，利用前缀和与二分查找定位到长段落内部。
 * 插图使用固定权重，使其可被进度条选中；这不是实际页码或像素滚动距离。
 * 长度计算须与 paragraphPartStarts 的文本拼接约定一致，才能与两种渲染器共享锚点。
 */
internal class ChapterSeekIndex(paragraphs: List<ReadingParagraph>, settings: ReaderSettings) {
    private val lengths = paragraphs.map { paragraph ->
        if(paragraph.imageUrl != null || paragraph.localImageId != null) 400
        else {
            val starts = paragraphPartStarts(paragraph, settings)
            ((starts.lastOrNull() ?: 0) + (paragraph.parts.lastOrNull()?.text?.length ?: 0) +
                if(settings.indent) 2 else 0).coerceAtLeast(1)
        }
    }
    private val starts = LongArray(lengths.size + 1).also { sums ->
        lengths.forEachIndexed { index, length -> sums[index + 1] = sums[index] + length }
    }

    /** 100% 定位到最后一个有效权重位置；滚动模式的精确末尾由界面额外处理。 */
    fun anchorAt(progress: Float): ChapterSeekAnchor {
        if(lengths.isEmpty()) return ChapterSeekAnchor(0, 0)
        val target = (progress.safeFraction().toDouble() * starts.last()).roundToLong().coerceAtMost(starts.last() - 1)
        var low = 0
        var high = lengths.size
        while(low + 1 < high) {
            val mid = (low + high) ushr 1
            if(starts[mid] <= target) low = mid else high = mid
        }
        return ChapterSeekAnchor(low, (target - starts[low]).toInt())
    }

    fun fractionAt(paragraph: Int, textOffset: Int): Float {
        if(lengths.isEmpty()) return 0f
        val index = paragraph.coerceIn(lengths.indices)
        return ((starts[index] + textOffset.coerceIn(0, lengths[index])).toDouble() / starts.last()).toFloat()
    }
}

internal fun Float.safeFraction(): Float = if(isFinite()) coerceIn(0f, 1f) else 0f

internal fun chapterSeekPage(progress: Float, count: Int): Int =
    (progress.safeFraction() * (count - 1).coerceAtLeast(0)).roundToInt()
