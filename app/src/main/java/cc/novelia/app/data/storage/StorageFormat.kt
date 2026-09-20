package cc.novelia.app.data.storage

import java.security.MessageDigest
import kotlinx.serialization.json.Json

val appJson = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true; explicitNulls = false }
fun hashName(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    val hex = "0123456789abcdef"
    return CharArray(digest.size * 2) { index ->
        val byte = digest[index / 2].toInt() and 0xff
        hex[if (index % 2 == 0) byte ushr 4 else byte and 0xf]
    }.concatToString()
}
