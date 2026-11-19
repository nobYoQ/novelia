package cc.novelia.app.reader

/** 与滚动列表的标题项对应；不占用正文的投影下标或原始段落编号。 */
internal const val CHAPTER_HEADING_PARAGRAPH = -1

/** 只比较章首完整段落；本地插图在章首时直接显示，避免补出的标题独占一页。 */
internal fun chapterHeading(title: String, paragraphs: List<ReadingParagraph>): String? {
    fun normalized(text: String) = text.filterNot { it.isWhitespace() }
    val heading = title.trim().takeIf { it.isNotEmpty() } ?: return null
    val first = paragraphs.firstOrNull()
    if (first?.localImageId != null) return null
    val alreadyPresent = first?.imageUrl == null &&
        first?.parts?.any { normalized(it.text) == normalized(heading) } == true
    return heading.takeUnless { alreadyPresent }
}
