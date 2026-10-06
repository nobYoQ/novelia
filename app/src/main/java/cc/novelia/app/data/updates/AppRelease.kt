package cc.novelia.app.data.updates

import cc.novelia.app.data.network.awaitText
import cc.novelia.app.data.storage.appJson
import java.math.BigInteger
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

const val APP_RELEASES_URL = "https://github.com/nobYoQ/novelia/releases"
private const val LATEST_RELEASE_API = "https://api.github.com/repos/nobYoQ/novelia/releases/latest"

private data class ReleaseVersion(val numbers: List<BigInteger>, val pre: List<String>) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        numbers.zip(other.numbers).forEach { (a, b) -> a.compareTo(b).takeIf { it != 0 }?.let { return it } }
        if(pre.isEmpty() || other.pre.isEmpty()) return when {
            pre.isEmpty() && other.pre.isEmpty() -> 0
            pre.isEmpty() -> 1
            else -> -1
        }
        pre.zip(other.pre).forEach { (a, b) ->
            val an = a.toBigIntegerOrNull(); val bn = b.toBigIntegerOrNull()
            val order = when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1
                bn != null -> 1
                else -> a.compareTo(b)
            }
            if(order != 0) return order
        }
        return pre.size.compareTo(other.pre.size)
    }
    companion object {
        fun parse(value: String): ReleaseVersion? {
            val match = Regex("^[vV]?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$")
                .matchEntire(value.trim()) ?: return null
            val pre = match.groupValues[4].takeIf(String::isNotEmpty)?.split('.') ?: emptyList()
            if(pre.any { it.all(Char::isDigit) && it.length > 1 && it.startsWith('0') }) return null
            return ReleaseVersion((1..3).map { match.groupValues[it].toBigInteger() }, pre)
        }
    }
}

fun isNewerAppVersion(current: String, candidate: String): Boolean {
    val installed = ReleaseVersion.parse(current) ?: return false
    val released = ReleaseVersion.parse(candidate) ?: return false
    return released > installed
}

@Serializable data class AppRelease(
    @SerialName("tag_name") val tag: String,
    @SerialName("html_url") val url: String,
    val name: String? = null, val body: String? = null,
    val draft: Boolean = false, val prerelease: Boolean = false,
) {
    fun availableFor(current: String): Boolean {
        val destination = url.toHttpUrlOrNull() ?: return false
        return !draft && !prerelease && isNewerAppVersion(current, tag) &&
            destination.scheme == "https" && destination.host == "github.com" &&
            destination.encodedPath.startsWith("/nobYoQ/novelia/releases/tag/")
    }
}

/** 独立公开客户端，不携带原站账号、镜像口令或 Cookie。 */
class AppReleaseClient(
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build(),
    private val endpoint: String = LATEST_RELEASE_API,
) {
    suspend fun latest(): AppRelease? {
        val request = Request.Builder().url(endpoint).header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "Novelia-Android").build()
        val response = client.newCall(request).awaitText()
        if(response.code == 404) return null
        if(response.code !in 200..299) throw IOException("更新检查失败（HTTP ${response.code}）")
        return appJson.decodeFromString<AppRelease>(response.text)
    }
}
