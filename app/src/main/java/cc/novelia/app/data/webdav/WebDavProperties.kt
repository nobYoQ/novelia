package cc.novelia.app.data.webdav

import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.xml.parsers.SAXParserFactory
import okhttp3.HttpUrl
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

/** 只读取目标资源的成功 DAV:getetag 属性；拒绝外部实体及超限 XML。 */
internal fun readWebDavPropertyEtag(data: ByteArray, target: HttpUrl): String? {
    return readWebDavProperties(data, target).etag
}

internal fun isWebDavCollection(data: ByteArray, target: HttpUrl): Boolean = readWebDavProperties(data, target).collection == true

private data class DavProperties(val etag: String? = null, val collection: Boolean? = null)

private fun readWebDavProperties(data: ByteArray, target: HttpUrl): DavProperties {
    try {
        if(data.size > 1024 * 1024) throw SAXException()
        val xml = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
        if(xml.contains("<!DOCTYPE", ignoreCase = true) || xml.contains("<!ENTITY", ignoreCase = true)) throw SAXException()
        val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
        // Android 与 JVM 的 SAX 实现支持的安全开关略有不同；实体解析仍由 handler 明确拒绝。
        for(feature in listOf("http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities")) {
            try { reader.setFeature(feature, false) } catch(_: SAXException) { }
        }
        val handler = PropertyHandler(target)
        reader.contentHandler = handler
        reader.entityResolver = handler
        reader.errorHandler = handler
        reader.parse(InputSource(StringReader(xml)))
        return handler.result
    } catch(_: Exception) {
        throw WebDavException(WebDavFailure.INVALID_DATA, "服务返回的 WebDAV 文件属性格式无效")
    }
}

private class PropertyHandler(private val target: HttpUrl) : DefaultHandler() {
    private val path = mutableListOf<String>()
    private val text = StringBuilder()
    private var elements = 0
    private var href: String? = null
    private var property: String? = null
    private var status: String? = null
    private var responseVersion: String? = null
    private var collection: Boolean? = null
    private var responseCollection: Boolean? = null
    var result = DavProperties()
        private set

    override fun resolveEntity(publicId: String?, systemId: String?): InputSource = throw SAXException()
    override fun error(e: SAXParseException) { throw e }
    override fun fatalError(e: SAXParseException) { throw e }

    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
        if(++elements > 4096 || path.size >= 64 || (attributes?.length ?: 0) > 64) throw SAXException()
        if(path.joinToString("/") in LEAVES) throw SAXException()
        path += if(uri == "DAV:") localName.orEmpty() else "!"
        if(path.size == 1 && path[0] != "multistatus") throw SAXException()
        when(path.joinToString("/")) {
            RESPONSE -> { href = null; responseVersion = null; responseCollection = null }
            PROPSTAT -> { property = null; status = null; collection = null }
            "$PROPSTAT/prop/resourcetype" -> collection = false
            "$PROPSTAT/prop/resourcetype/collection" -> collection = true
        }
        text.setLength(0)
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if(path.joinToString("/") in LEAVES) {
            if(text.length + length > 4096) throw SAXException()
            text.append(ch, start, length)
        }
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        when(path.joinToString("/")) {
            "$RESPONSE/href" -> {
                if(href != null) throw SAXException()
                href = text.toString().trim()
            }
            "$PROPSTAT/prop/getetag" -> {
                if(property != null) throw SAXException()
                property = text.toString().trim()
            }
            "$PROPSTAT/status" -> {
                if(status != null) throw SAXException()
                status = text.toString().trim()
            }
            PROPSTAT -> if(status?.matches(Regex("HTTP/[0-9.]+\\s+200(?:\\s+.*)?")) == true) {
                if(property != null) {
                    if(responseVersion != null && responseVersion != property) throw SAXException()
                    responseVersion = property
                }
                if(collection != null) {
                    if(responseCollection != null && responseCollection != collection) throw SAXException()
                    responseCollection = collection
                }
            }
            RESPONSE -> {
                val resolved = href?.let(target::resolve)
                if(resolved != null && resolved.scheme == target.scheme && resolved.host == target.host && resolved.port == target.port &&
                    resolved.username.isEmpty() && resolved.password.isEmpty() && resolved.query == null && resolved.fragment == null &&
                    resolved.encodedPath.trimEnd('/') == target.encodedPath.trimEnd('/')) {
                    if(result.etag != null && responseVersion != null && result.etag != responseVersion) throw SAXException()
                    if(result.collection != null && responseCollection != null && result.collection != responseCollection) throw SAXException()
                    result = DavProperties(responseVersion ?: result.etag, responseCollection ?: result.collection)
                }
            }
        }
        path.removeAt(path.lastIndex)
        text.setLength(0)
    }

    companion object {
        private const val RESPONSE = "multistatus/response"
        private const val PROPSTAT = "$RESPONSE/propstat"
        private val LEAVES = setOf("$RESPONSE/href", "$PROPSTAT/prop/getetag", "$PROPSTAT/status")
    }
}
