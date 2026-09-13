package cc.novelia.app.data

/** Templates and toggle semantics from the original site's MarkdownToolbar.vue. */
internal enum class MarkdownTemplate(val label: String, val prefix: String, val suffix: String, val placeholder: String, val block: Boolean = false) {
    Bold("粗体", "**", "**", "粗体"),
    Italic("斜体", "*", "*", "斜体"),
    Strike("删除线", "~~", "~~", "删除线"),
    Link("链接", "[", "](链接)", ""),
    Spoiler("剧透", "!!", "!!", "剧透"),
    Star("评分", "", "", "::: star 5", true),
    Details("折叠内容", "::: details 点击展开\n", "\n:::", "折叠内容", true);
}

internal data class MarkdownEdit(val text: String, val selectionStart: Int, val selectionEnd: Int)

internal fun applyMarkdownTemplate(text: String, start: Int, end: Int, template: MarkdownTemplate, limit: Int): MarkdownEdit {
    val original = MarkdownEdit(text, start, end)
    var before = text.substring(0, minOf(start, end))
    var middle = text.substring(minOf(start, end), maxOf(start, end))
    var after = text.substring(maxOf(start, end))
    with(template) {
        when {
            this == MarkdownTemplate.Star -> {
                if (before.isNotEmpty() && !before.endsWith('\n')) before += "\n"
                if (!after.startsWith('\n')) after = "\n$after"
                middle = placeholder
            }
            before.endsWith(prefix) && after.startsWith(suffix) -> {
                before = before.dropLast(prefix.length)
                after = after.drop(suffix.length)
            }
            middle.length >= prefix.length + suffix.length && middle.startsWith(prefix) && middle.endsWith(suffix) ->
                middle = middle.substring(prefix.length, middle.length - suffix.length)
            else -> {
                if (block) {
                    if (before.isNotEmpty() && !before.endsWith('\n')) before += "\n"
                    if (!after.startsWith('\n')) after = "\n$after"
                }
                before += prefix
                after = suffix + after
                if (middle.isEmpty()) middle = placeholder
            }
        }
    }
    val result = before + middle + after
    return if (result.length <= limit) MarkdownEdit(result, before.length, before.length + middle.length) else original
}
