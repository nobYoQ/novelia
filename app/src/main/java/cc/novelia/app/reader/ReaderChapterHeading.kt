package cc.novelia.app.reader

/** 与滚动列表的标题项对应；不占用正文的投影下标或原始段落编号。 */
internal const val CHAPTER_HEADING_PARAGRAPH = -1

/** 只合并空白后比较章首完整段落，避免把正文中提及标题的句子误判为标题。 */
internal fun chapterHeading(title: String, paragraphs: List<ReadingParagraph>): String? {
    fun normalized(text: String) = text.filterNot { it.isWhitespace() }
    val heading = title.trim().takeIf { it.isNotEmpty() } ?: return null
    val first = paragraphs.firstOrNull()
    val alreadyPresent = first?.imageUrl == null && first?.localImageId == null &&
        first?.parts?.any { normalized(it.text) == normalized(heading) } == true
    return heading.takeUnless { alreadyPresent }
}
