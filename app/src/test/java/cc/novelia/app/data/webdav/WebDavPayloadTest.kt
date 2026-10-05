package cc.novelia.app.data.webdav

import org.junit.Assert.*
import org.junit.Test

class WebDavPayloadTest {
    @Test fun accepts64LayersAndRejects65BeforeDecoding() {
        val accepted = "[".repeat(64) + "0" + "]".repeat(64)
        assertEquals(accepted, checkedWebDavJson(accepted.toByteArray()))
        val deep = "[".repeat(65) + "0" + "]".repeat(65)
        assertInvalid(deep)
        assertInvalid("[".repeat(100_000) + "0" + "]".repeat(100_000))
    }

    @Test fun bracesQuotesAndEscapedBackslashesInsideStringsDoNotCountAsContainers() {
        val body = """{"note":"[[[[ { } ]]]]\n\"quoted\"\\tail","nested":{"quote":"\u4e2d\u6587"}}"""
        assertEquals(body, checkedWebDavJson(body.toByteArray()))
        val manyBraces = """{"note":"${"[{}]".repeat(5000)}"}"""
        assertEquals(manyBraces, checkedWebDavJson(manyBraces.toByteArray()))
    }

    @Test fun truncatedContainersStringsAndUnicodeEscapesAreRejected() {
        listOf("{", "[", "{\"note\":\"abc", "{\"note\":\"abc\\", "{\"note\":\"\\u12", "{\"nested\":[1,2]", "\"unterminated").forEach(::assertInvalid)
    }

    @Test fun mismatchedContainersInvalidEscapesAndControlCharactersAreRejected() {
        listOf("{]", "[}", "}", "{\"note\":\"\\x\"}", "{\"note\":\"\\uQ000\"}", "{\"note\":\"line\nfeed\"}", "{\u0000}").forEach(::assertInvalid)
    }

    @Test fun invalidUtf8CannotSilentlyReplaceUserText() {
        try { checkedWebDavJson(byteArrayOf(0x7b, 0xc3.toByte(), 0x28, 0x7d)); fail("Expected invalid UTF-8") }
        catch(error: WebDavException) { assertEquals(WebDavFailure.INVALID_DATA, error.failure) }
    }

    @Test fun emptyAndWhitespaceOnlyFilesAreRejected() {
        assertInvalid("")
        assertInvalid(" \t\r\n")
    }

    @Test fun chineseAndLegalWhitespaceArePreservedExactly() {
        val body = "{\n\t\"note\":\"中文与🙂\",\r\n \"value\":true\n}"
        assertEquals(body, checkedWebDavJson(body.toByteArray()))
    }

    @Test fun callerCanUseSeparateCapacityForCombinedLocalCache() {
        val body = "{\"note\":\"中文\"}".toByteArray()
        assertEquals(body.toString(Charsets.UTF_8), checkedWebDavJson(body, maxBytes = body.size))
        try { checkedWebDavJson(body, maxBytes = body.size - 1); fail("Expected configured capacity limit") }
        catch(error: WebDavException) { assertEquals(WebDavFailure.INVALID_DATA, error.failure) }
    }

    private fun assertInvalid(body: String) {
        try { checkedWebDavJson(body.toByteArray()); fail("Expected rejected payload") }
        catch(error: WebDavException) { assertEquals(WebDavFailure.INVALID_DATA, error.failure) }
    }
}
