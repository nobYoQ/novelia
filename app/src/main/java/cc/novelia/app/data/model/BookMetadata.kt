package cc.novelia.app.data.model

/** 部分元数据响应不得抹去已有的更新时间或封面分类。 */
fun BookCard.withKnownUpdateTime(previous: BookCard?): BookCard = copy(
    updateAt = updateAt?.takeIf { it > 0 }
        ?: previous?.takeIf { it.ref == ref }?.updateAt?.takeIf { it > 0 },
    novelType = novelType?.takeIf { it.isNotBlank() } ?: previous?.takeIf { it.ref == ref }?.novelType,
    attentions = attentions ?: previous?.takeIf { it.ref == ref }?.attentions,
)

/** 列表中的粗略历史标记只有时间更新时，才可替换已知的具体章节。 */
fun CloudReadingProgress.withKnownChapter(previous: CloudReadingProgress?): CloudReadingProgress {
    if(chapterResolved || previous?.account != account || !previous.chapterResolved || !previous.hasHistory) return this
    return if((lastReadAt ?: 0) <= (previous.lastReadAt ?: 0)) previous else this
}
