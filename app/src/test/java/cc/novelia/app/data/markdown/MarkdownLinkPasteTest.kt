package cc.novelia.app.data.markdown

import org.junit.Assert.*
import org.junit.Test

class MarkdownLinkPasteTest {
    @Test fun normalizesHttpUrlsAndPreservesQueryAndFragmentWithReversedSelection() {
        val expected = "[选中内容](https://example.com/a%28b%29?q=one&x=two#part)"
        assertEquals(expected, markdownLinkForPaste("前文选中内容后文", 6, 2,
            " \nHTTPS://EXAMPLE.COM/a(b)?q=one&x=two#part\t", 20000))
        assertEquals("[字](http://example.com/)", markdownLinkForPaste("字", 0, 1, "http://example.com", 100))
    }

    @Test fun escapesLinkLabelsWithoutChangingChineseOrEmoji() {
        val text = "[标签]\\😀"
        assertEquals("[\\[标签\\]\\\\😀](https://example.com/)", markdownLinkForPaste(text, 0, text.length, "https://example.com", 100))
    }

    @Test fun unsupportedClipboardAndSelectionsFallBackToNormalPaste() {
        for (clipboard in listOf("普通文字", "www.example.com", "ftp://example.com", "javascript:alert(1)",
            "data:text/plain,hello", "https://", "https://example.com:99999/", "https://example.com/a b", "https://example.com/\nother")) {
            assertNull(clipboard, markdownLinkForPaste("文字", 0, 2, clipboard, 20000))
        }
        assertNull(markdownLinkForPaste("文字", 1, 1, "https://example.com", 20000))
        assertNull(markdownLinkForPaste("多\n行", 0, 3, "https://example.com", 20000))
        assertNull(markdownLinkForPaste("多\r行", 0, 3, "https://example.com", 20000))
        assertNull(markdownLinkForPaste("文字", -1, 2, "https://example.com", 20000))
        assertNull(markdownLinkForPaste("文字", 0, 3, "https://example.com", 20000))
        assertNull(markdownLinkForPaste("😀", 1, 2, "https://example.com", 20000))
    }

    @Test fun enforcesTheWholeDocumentLimitUsingTheEditorsCountingRule() {
        val text = "😀前选中后"
        val link = "[选中](https://example.com/)"
        val updated = "😀前${link}后"
        val codePoints = updated.codePointCount(0, updated.length)
        assertEquals(link, markdownLinkForPaste(text, 3, 5, "https://example.com", codePoints, unicodeLength = true))
        assertNull(markdownLinkForPaste(text, 3, 5, "https://example.com", codePoints - 1, unicodeLength = true))
        assertNull(markdownLinkForPaste(text, 3, 5, "https://example.com", codePoints))
        assertEquals(link, markdownLinkForPaste(text, 3, 5, "https://example.com", updated.length))
    }

    @Test fun escapingAndUrlNormalizationCannotSilentlyExceedTheLimit() {
        val link = "[\\[x\\]](https://example.com/)"
        assertEquals(link, markdownLinkForPaste("[x]", 0, 3, "https://example.com", link.length))
        assertNull(markdownLinkForPaste("[x]", 0, 3, "https://example.com", link.length - 1))
    }
}
