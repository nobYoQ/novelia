package cc.novelia.app

import cc.novelia.app.data.catalog.KeywordCatalog
import cc.novelia.app.data.catalog.SearchExpression
import org.junit.Assert.*
import org.junit.Test

class SearchExpressionBoundaryTest {
    /** Mirrors the upstream boundary before tokens are passed to its simpleQueryString query. */
    private fun siteFilters(expression: String): List<String> = expression.split(" ").filter { token ->
        ((token.startsWith('>') || token.startsWith('<')) && token.substring(1).toUIntOrNull() != null) || token.endsWith('$')
    }

    @Test fun ordinaryWordsNeverBecomeTagOrChapterFilters() {
        val expression = SearchExpression.build("price$ >20 <50", "one$ >8", "", "cost$ <6", "", "", "", "")
        assertEquals("\"price$\" \\>20 \\<50 (\"one$\" | \\>8) -\"cost$\" -\\<6", expression)
        assertTrue(siteFilters(expression).isEmpty())
    }

    @Test fun dollarAndNumericTokensInsideAnExactPhraseStayInThePhrase() {
        val original = "pay$ >20 now <50 end$"
        val expression = SearchExpression.build("", "", original, "", "", "", "", "")
        assertTrue(siteFilters(expression).isEmpty())
        // Lucene's phrase parser consumes a backslash and copies the next character literally.
        val decoded = expression.removeSurrounding("\"").replace(Regex("\\\\(.)")) { it.groupValues[1] }
        assertEquals(original, decoded)
    }

    @Test fun literalDollarSignsInsideOrAtTheEndOfTagsRoundTripThroughTheSiteSuffix() {
        val tags = listOf("中\$间", "末尾$", ">20")
        assertTrue(tags.all(KeywordCatalog::canSearch))
        val expression = SearchExpression.build("", "", "", "", tags.joinToString(" "), "除外$", "5", "100")
        assertEquals(listOf("中\$间$", "末尾$$", ">20$", "-除外$$", ">5", "<100"), siteFilters(expression))
        assertEquals(tags + "除外$", SearchExpression.tagsIn(expression))
    }
}
