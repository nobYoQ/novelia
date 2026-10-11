package cc.novelia.app.data.markdown

import org.junit.Assert.*
import org.junit.Test

class MarkdownTemplatesTest {
    @Test fun emptyTemplatesMatchTheOriginalSiteAndSelectThePlaceholder() {
        val expected = listOf("**粗体**", "*斜体*", "~~删除线~~", "[](链接)", "!!剧透!!", "::: star 5\n", "::: details 点击展开\n折叠内容\n:::\n")
        MarkdownTemplate.entries.zip(expected).forEach { (template, source) ->
            val edit = applyMarkdownTemplate("", 0, 0, template, 20000)
            assertEquals(source, edit.text)
            assertEquals(template.placeholder, edit.text.substring(edit.selectionStart, edit.selectionEnd))
        }
    }

    @Test fun formattingAndUnformattingPreserveSurroundingTextAndChineseSelection() {
        val original = "前文选中内容后文"
        listOf(MarkdownTemplate.Bold, MarkdownTemplate.Italic, MarkdownTemplate.Strike, MarkdownTemplate.Link, MarkdownTemplate.Spoiler).forEach { template ->
            val edit = applyMarkdownTemplate(original, 6, 2, template, 20000)
            assertEquals("前文${template.prefix}选中内容${template.suffix}后文", edit.text)
            val undone = applyMarkdownTemplate(edit.text, edit.selectionStart, edit.selectionEnd, template, 20000)
            assertEquals(original, undone.text)
            assertEquals(MarkdownEdit(original, 2, 6), undone)
        }
        assertEquals(MarkdownEdit("选中内容", 0, 4), applyMarkdownTemplate("**选中内容**", 0, 8, MarkdownTemplate.Bold, 20000))
    }

    @Test fun blockTemplatesKeepAdjacentParagraphsAndCanBeRemoved() {
        val edit = applyMarkdownTemplate("前文内容后文", 2, 4, MarkdownTemplate.Details, 20000)
        assertEquals("前文\n::: details 点击展开\n内容\n:::\n后文", edit.text)
        assertEquals("前文\n内容\n后文", applyMarkdownTemplate(edit.text, edit.selectionStart, edit.selectionEnd, MarkdownTemplate.Details, 20000).text)
        assertEquals("前文\n::: star 5\n后文", applyMarkdownTemplate("前文内容后文", 2, 4, MarkdownTemplate.Star, 20000).text)
    }

    @Test fun limitsRejectTheWholeTemplateWithoutTruncatingTheDraftOrSelection() {
        val original = "正文".repeat(5000)
        assertEquals(MarkdownEdit(original, 3, 7), applyMarkdownTemplate(original, 3, 7, MarkdownTemplate.Spoiler, 10000))
    }
}
