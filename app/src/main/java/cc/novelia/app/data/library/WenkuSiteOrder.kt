package cc.novelia.app.data.library

import cc.novelia.app.data.model.LibraryState
import cc.novelia.app.data.model.SavedBook
import cc.novelia.app.data.model.WenkuDetail
import java.text.Normalizer
import java.util.Locale
import com.ibm.icu.text.Collator
import com.ibm.icu.text.RuleBasedCollator
import com.ibm.icu.util.ULocale

/** 与网站资源目录相同：日文译文分卷在前，中文资源在后。 */
fun WenkuDetail.siteVolumeIds(): List<String> = siteVolumeIds(
    volumeJp.map { "jp:${it.volumeId}" } + volumeZh.map { "zh:$it" })

/** 原站对日文卷名做 NFKC、括号备注去除和日语数字排序，中文资源保持接口顺序。 */
fun siteVolumeIds(ids: List<String>): List<String> {
    val collator = (Collator.getInstance(ULocale.JAPANESE) as RuleBasedCollator).apply {
        numericCollation = true
        strength = Collator.PRIMARY
    }
    fun normalized(id: String): String = Normalizer.normalize(id.removePrefix("jp:"), Normalizer.Form.NFKC)
        .replace(Regex("\\(([0-9]+)\\)|\\[([0-9]+)\\]")) { match ->
            (match.groups[1]?.value ?: match.groups[2]!!.value).padStart(2, '0')
        }
        .replace(Regex("[0-9]+")) { it.value.padStart(2, '0') }
        .replace(Regex("\\[[^]]*]|【[^】]*】|\\([^)]*\\)|\\s+"), "")
    val japanese = ids.filter { it.startsWith("jp:") }.distinct().sortedWith { a, b -> collator.compare(normalized(a), normalized(b)) }
    return japanese + ids.filterNot { it.startsWith("jp:") }.distinct()
}

private fun volumeName(value: String): String = Normalizer.normalize(
    value.removePrefix("jp:").removePrefix("zh:")
        .replace(Regex("(?i)\\.(epub|txt|srt)$"), "")
        .replace(Regex("^(jp|zh|mix|mix-reverse)\\."), ""), Normalizer.Form.NFKC
).lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

/** 优先使用下载来源 ID；旧导入卷按完整文件名或标题匹配，不猜测数字卷号。 */
internal fun siteVolumeRank(volume: SavedBook, siteIds: List<String>): Int? {
    val source = volume.sourceVolumeId
    if(source != null) {
        val exact = siteIds.indexOfFirst { it == source || it.substringAfter(':') == source }
        if(exact >= 0) return exact
    }
    val names = listOfNotNull(source, volume.book.title, volume.book.originalTitle.takeIf(String::isNotBlank))
        .map(::volumeName).filter(String::isNotEmpty).toSet()
    return siteIds.indexOfFirst { volumeName(it) in names }.takeIf { it >= 0 }
}

/** 不可匹配的卷保留已有相对顺序并置于末尾；倒序只反转网站可匹配部分。 */
fun LibraryState.withWenkuSiteOrder(parentKey: String, siteIds: List<String>, descending: Boolean = false): LibraryState {
    val parent = books.firstOrNull { it.book.ref.key == parentKey && it.book.ref.isWenku }
        ?: error("请先收藏目标文库小说")
    val previous = parent.volumeOrder.withIndex().associate { it.value to it.index }
    val volumes = books.filter { it.book.ref.isLocal && it.parentWenkuKey == parentKey }
        .sortedWith { a, b ->
            val order = (previous[a.book.ref.key] ?: Int.MAX_VALUE).compareTo(previous[b.book.ref.key] ?: Int.MAX_VALUE)
            if(order != 0) order else compareVolumeTitles(a.book.title, b.book.title)
        }
    val ranks = volumes.associate { it.book.ref.key to siteVolumeRank(it, siteIds) }
    val matched = volumes.filter { ranks[it.book.ref.key] != null }.sortedWith { a, b ->
        val order = ranks.getValue(a.book.ref.key)!!.compareTo(ranks.getValue(b.book.ref.key)!!)
        if(descending) -order else order
    }
    val ordered = matched + volumes.filter { ranks[it.book.ref.key] == null }
    return withWenkuVolumeOrder(parentKey, ordered.map { it.book.ref.key }).let { state ->
        state.copy(books = state.books.map { saved ->
            if(saved.book.ref.key == parentKey) saved.copy(siteVolumeOrderDescending = descending,
                book = saved.book.copy(volumeIds = siteIds)) else saved
        })
    }
}
