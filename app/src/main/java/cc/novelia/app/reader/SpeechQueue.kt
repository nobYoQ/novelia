package cc.novelia.app.reader

/** Sentence-sized utterances let pause/resume repeat only the current sentence. */
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
            for(cursor in start until end) {
                if((cursor - start) % 256 == 0) checkCancelled()
                val char = text[cursor]
                val sentenceEnd = char in "。！？!?\n" || (char == '.' && (cursor + 1 == text.length || text[cursor + 1].isWhitespace()))
                if(sentenceEnd) {
                    var boundary = cursor + 1
                    while(boundary < end && text[boundary] in "”’」』\"')）") boundary++
                    end = boundary
                    break
                }
            }
            add(text.substring(start, end))
            start = end
        }
    }
}
