package cc.novelia.app.reader

/**
 * 将正文分成适合 TTS 的句子队列，暂停后恢复最多重复当前句，跳过空白与图片标记。
 * 每段最多取 3500 个 UTF-16 单元，并避免从代理对中间切开；优先在句末及其闭引号后断开。
 * 句号只在段尾或后接空白时视作边界，减少小数、域名等内容被错误切断的情况。
 */
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
