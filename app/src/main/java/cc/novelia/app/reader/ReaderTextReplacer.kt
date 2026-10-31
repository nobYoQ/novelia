package cc.novelia.app.reader

/** 精确匹配、最长词优先、单次替换；替换结果不再次进入匹配，避免词条互相递归。 */
internal class ReaderTextReplacer(rules: Map<String, String>) {
    private class Node(val children: MutableMap<Char, Node> = mutableMapOf(), var replacement: String? = null)
    private val root = Node()
    init {
        rules.forEach { (from, to) ->
            var node = root
            from.forEach { node = node.children.getOrPut(it) { Node() } }
            node.replacement = to
        }
    }

    /** 返回 null 时保留原段，并由阅读器提示未应用；不截断正文或图片标记。 */
    fun replace(text: String, maxCharacters: Int = 2_000_000,
        onReplacement: (Int, Int, String) -> Unit = { _, _, _ -> }, checkCancelled: () -> Unit = {}): String? {
        if(root.children.isEmpty()) return text
        if(text.length > maxCharacters) return null
        val result = StringBuilder(text.length)
        var start = 0
        var iterations = 0
        while(start < text.length) {
            if(iterations++ % 256 == 0) checkCancelled()
            var node = root
            var end = start
            var matchedEnd = start
            var replacement: String? = null
            while(end < text.length) {
                node = node.children[text[end]] ?: break
                end++
                node.replacement?.let { replacement = it; matchedEnd = end }
            }
            val target = replacement
            if(result.length + (target?.length ?: 1) > maxCharacters) return null
            if(target == null) result.append(text[start++])
            else { onReplacement(start, matchedEnd, target); result.append(target); start = matchedEnd }
        }
        checkCancelled()
        return result.toString()
    }
}
