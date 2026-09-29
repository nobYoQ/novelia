package cc.novelia.app.data.model

/** Partial metadata responses must not erase a previously known update time or cover classification. */
fun BookCard.withKnownUpdateTime(previous: BookCard?): BookCard = copy(
    updateAt = updateAt?.takeIf { it > 0 }
        ?: previous?.takeIf { it.ref == ref }?.updateAt?.takeIf { it > 0 },
    novelType = novelType?.takeIf { it.isNotBlank() } ?: previous?.takeIf { it.ref == ref }?.novelType,
    attentions = attentions ?: previous?.takeIf { it.ref == ref }?.attentions,
)

/** A coarse list marker must not discard a known chapter unless its timestamp is newer. */
fun CloudReadingProgress.withKnownChapter(previous: CloudReadingProgress?): CloudReadingProgress {
    if(chapterResolved || previous?.account != account || !previous.chapterResolved || !previous.hasHistory) return this
    return if((lastReadAt ?: 0) <= (previous.lastReadAt ?: 0)) previous else this
}
