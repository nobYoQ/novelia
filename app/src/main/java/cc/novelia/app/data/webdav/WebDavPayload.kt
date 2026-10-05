package cc.novelia.app.data.webdav

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * 在反序列化之前限制递归深度，并拒绝损坏的 UTF-8、字符串和容器。
 * 此处不替代 JSON 语法与协议模型校验；括号及转义计数只针对字符串之外的内容。
 */
internal fun checkedWebDavJson(data: ByteArray, maxBytes: Int = WebDavClient.MAX_RESPONSE_BYTES): String {
    require(maxBytes > 0) { "同步文件容量限制无效" }
    fun invalid(message: String): Nothing = throw WebDavException(WebDavFailure.INVALID_DATA, message)
    if(data.isEmpty()) invalid("远端同步文件为空，已停止同步")
    if(data.size > maxBytes) invalid("同步文件过大，已停止同步")
    val text = try {
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
    } catch(_: CharacterCodingException) { invalid("远端同步文件的文字编码无效，已停止同步") }
    val stack = CharArray(64)
    var depth = 0
    var insideString = false
    var index = 0
    var hasContent = false
    while(index < text.length) {
        val character = text[index]
        if(insideString) {
            when {
                character == '"' -> insideString = false
                character == '\\' -> {
                    index++
                    if(index >= text.length) invalid("远端同步文件未完整保存，已停止同步")
                    when(text[index]) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                        'u' -> {
                            if(index + 4 >= text.length) invalid("远端同步文件未完整保存，已停止同步")
                            for(offset in 1..4) if(text[index + offset] !in '0'..'9' && text[index + offset] !in 'a'..'f' && text[index + offset] !in 'A'..'F') {
                                invalid("远端同步文件含有无效文字，已停止同步")
                            }
                            index += 4
                        }
                        else -> invalid("远端同步文件含有无效文字，已停止同步")
                    }
                }
                character.code < 0x20 -> invalid("远端同步文件含有无效文字，已停止同步")
            }
        } else {
            if(!character.isWhitespace()) hasContent = true
            when(character) {
                '"' -> insideString = true
                '{', '[' -> {
                    if(depth == stack.size) invalid("远端同步文件结构过于复杂，已停止同步")
                    stack[depth++] = character
                }
                '}', ']' -> {
                    if(depth == 0 || stack[depth - 1] != if(character == '}') '{' else '[') {
                        invalid("远端同步文件结构损坏，已停止同步")
                    }
                    depth--
                }
                else -> if(character.code < 0x20 && character !in "\r\n\t") invalid("远端同步文件含有无效文字，已停止同步")
            }
        }
        index++
    }
    if(!hasContent) invalid("远端同步文件为空，已停止同步")
    if(insideString || depth != 0) invalid("远端同步文件未完整保存，已停止同步")
    return text
}
