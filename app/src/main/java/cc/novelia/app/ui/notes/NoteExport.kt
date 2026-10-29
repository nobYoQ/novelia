package cc.novelia.app.ui.notes

internal enum class NoteExportFormat(val extension: String, val mime: String) {
    Markdown("md", "text/markdown"), Text("txt", "text/plain")
}

/** 输入是用户当前筛选的快照；按书籍身份分组，标题相同的不同书籍不会混在一起。 */
internal fun exportNotes(notes: List<NotePresentation>, format: NoteExportFormat, checkCancelled: () -> Unit = {}): String = buildString {
    val markdown = format == NoteExportFormat.Markdown
    fun plain(text: String) = if(markdown) text.replace(Regex("([\\\\`*_{}\\[\\]<>#!|~])"), "\\\\$1") else text
    append(if(markdown) "# " else "").append("Novelia 书签与批注\n\n共 ${notes.size} 条\n\n")
    for(book in notes.groupBy { it.note.key }.values) {
        checkCancelled()
        append(if(markdown) "## " else "书籍：").append(plain(book.first().bookTitle)).append("\n\n")
        for(item in book) {
            checkCancelled()
            val note = item.note
            append(if(markdown) "### " else "章节：").append(plain(item.chapterTitle)).append(" · 第 ${note.paragraph + 1} 段\n\n")
            val source = when(note.selection?.source) {
                "original" -> "原文"; "gpt" -> "GPT 译文"; "sakura" -> "Sakura 译文"; "youdao" -> "有道译文"
                else -> note.selection?.source
            }
            append(if(note.selection != null) "划线" else "书签")
            if(note.text.isNotBlank()) append(" · 批注")
            if(source != null) append(" · ").append(plain(source))
            append("\n\n")
            note.quote.lineSequence().forEach { append(if(markdown) "> " else "").append(plain(it)).append('\n') }
            append('\n')
            if(note.text.isNotBlank()) append("批注：\n").append(plain(note.text)).append("\n\n")
            append("---\n\n")
            require(length <= 8_000_000) { "导出内容过多，请按书籍或关键词筛选后分批导出" }
        }
    }
}
