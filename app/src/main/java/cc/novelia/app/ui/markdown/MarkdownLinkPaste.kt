package cc.novelia.app.ui.markdown

import android.content.ClipData
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.input.TextFieldValue
import cc.novelia.app.data.markdown.markdownLinkForPaste

/** 仅为当前输入框提供粘贴内容，不改写系统剪贴板；选区、光标和撤销仍由 TextField 处理。 */
@Composable internal fun MarkdownLinkPaste(value: TextFieldValue, focused: Boolean, limit: Int,
    unicodeLength: Boolean = false, content: @Composable () -> Unit) {
    val clipboard = LocalClipboard.current
    val latestValue by rememberUpdatedState(value)
    val latestFocused by rememberUpdatedState(focused)
    val latestLimit by rememberUpdatedState(limit)
    val latestUnicodeLength by rememberUpdatedState(unicodeLength)
    val localClipboard = remember(clipboard) {
        object : Clipboard by clipboard {
            override suspend fun getClipEntry(): ClipEntry? {
                val before = latestValue
                val entry = clipboard.getClipEntry() ?: return null
                if(!latestFocused || before != latestValue || before.composition != null || entry.clipData.itemCount != 1) return entry
                val text = entry.clipData.getItemAt(0).text?.toString() ?: return entry
                val link = markdownLinkForPaste(before.text, before.selection.start, before.selection.end,
                    text, latestLimit, latestUnicodeLength) ?: return entry
                return ClipEntry(ClipData.newPlainText("链接", link))
            }
        }
    }
    CompositionLocalProvider(LocalClipboard provides localClipboard, content = content)
}
