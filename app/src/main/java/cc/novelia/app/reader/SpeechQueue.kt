package cc.novelia.app.reader

/** Keep images and blank paragraphs out of TTS, without splitting a surrogate pair. */
internal fun prepareSpeechQueue(paragraphs: List<String>, checkCancelled: () -> Unit = {}): List<String> = buildList {
    for(paragraph in paragraphs) {
        checkCancelled()
        val text = paragraph.trim()
        if(text.isEmpty() || text.startsWith("novelia-image:") || text.startsWith("<图片>")) continue
        var start = 0
        while(start < text.length) {
            checkCancelled()
            var end = (start + 3500).coerceAtMost(text.length)
            if(end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            add(text.substring(start, end))
            start = end
        }
    }
}
