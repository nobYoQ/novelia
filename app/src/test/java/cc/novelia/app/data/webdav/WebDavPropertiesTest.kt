package cc.novelia.app.data.webdav

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class WebDavPropertiesTest {
    private val target = "https://example.test/dav/novelia-sync/settings.json".toHttpUrl()
    private fun response(href: String = target.encodedPath, etag: String = "&quot;v1&quot;", status: String = "200 OK", namespace: String = "DAV:") =
        """<d:response><d:href>$href</d:href><d:propstat><d:prop><x:getetag xmlns:x="$namespace">$etag</x:getetag></d:prop><d:status>HTTP/1.1 $status</d:status></d:propstat></d:response>"""
    private fun parse(responses: String) = readWebDavPropertyEtag("""<d:multistatus xmlns:d="DAV:">$responses</d:multistatus>""".toByteArray(), target)

    @Test fun readsOnlySuccessfulPropertyOfRequestedResource() {
        val xml = response("/dav/novelia-sync/other.json", "&quot;other&quot;") + response(status = "404 Not Found") + response()
        assertEquals("\"v1\"", parse(xml))
        assertNull(parse(response(namespace = "urn:unrelated")))
        assertNull(parse(response(status = "403 Forbidden")))
    }

    @Test fun absoluteHrefMustBeSameOriginAndFile() {
        assertEquals("\"v1\"", parse(response(target.toString())))
        assertNull(parse(response("https://other.test${target.encodedPath}")))
        assertNull(parse(response("http://example.test${target.encodedPath}")))
        assertNull(parse(response("${target}?token=ignored")))
    }

    @Test fun namespacePrefixIsIrrelevantAndEntitiesAreDecoded() {
        val xml = """<multistatus xmlns="DAV:"><response><href>${target.encodedPath}</href><propstat><prop><getetag>&quot;a&amp;b&quot;</getetag></prop><status>HTTP/1.1 200 OK</status></propstat></response></multistatus>"""
        assertEquals("\"a&b\"", readWebDavPropertyEtag(xml.toByteArray(), target))
    }

    @Test fun conflictingVersionsAndNestedPropertyTextAreRejected() {
        for(xml in listOf(response() + response(etag = "&quot;v2&quot;"), response(etag = "before<d:other/>after"))) {
            val error = assertThrows(WebDavException::class.java) { parse(xml) }
            assertEquals(WebDavFailure.INVALID_DATA, error.failure)
        }
    }

    @Test fun externalAndInternalEntityDeclarationsAreRejected() {
        for(declaration in listOf("<!DOCTYPE d:multistatus SYSTEM 'file:///never-read'>", "<!DOCTYPE d:multistatus [<!ENTITY x 'expanded'>]>")) {
            val error = assertThrows(WebDavException::class.java) {
                readWebDavPropertyEtag((declaration + "<d:multistatus xmlns:d='DAV:'/>").toByteArray(), target)
            }
            assertEquals(WebDavFailure.INVALID_DATA, error.failure)
        }
    }

    @Test fun malformedEncodingOversizedAndExcessivelyNestedXmlAreRejected() {
        val invalid = listOf(byteArrayOf(0xc3.toByte(), 0x28), ByteArray(1024 * 1024 + 1),
            ("<d:multistatus xmlns:d='DAV:'>" + "<d:x>".repeat(64) + "</d:x>".repeat(64) + "</d:multistatus>").toByteArray(),
            "<multistatus>broken".toByteArray())
        invalid.forEach { data -> assertEquals(WebDavFailure.INVALID_DATA,
            assertThrows(WebDavException::class.java) { readWebDavPropertyEtag(data, target) }.failure) }
    }

    @Test fun normalizationKeepsWeakAndMalformedVersionsUnusable() {
        assertEquals("\"abc-12:9\"", WebDavClient.normalizeReturnedEtag("abc-12:9"))
        assertEquals("\"quoted\"", WebDavClient.normalizeReturnedEtag("\"quoted\""))
        listOf("W/\"v1\"", "w/v1", "*", "\"v1\", \"v2\"", "v1\r\n", "v1 v2", "").forEach {
            assertNull(WebDavClient.normalizeReturnedEtag(it))
        }
    }
}
