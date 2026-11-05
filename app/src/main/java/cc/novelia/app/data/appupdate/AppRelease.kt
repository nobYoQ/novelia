package cc.novelia.app.data.appupdate

import cc.novelia.app.BuildConfig
import cc.novelia.app.data.network.newNetworkCall

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
private const val PREVIEW_RELEASE_API = "https://api.github.com/repos/nobYoQ/novelia/releases/tags/preview"

class AppReleaseException(override val message: String) : IOException(message)

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

internal fun compareAppVersions(candidate: String, current: String): Int? {
    val a = ReleaseVersion.parse(candidate) ?: return null
    val b = ReleaseVersion.parse(current) ?: return null
    return a.compareTo(b)
}

@Serializable data class AppRelease(
    @SerialName("tag_name") val tag: String,
    @SerialName("html_url") val url: String,
    val name: String? = null, val body: String? = null,
    val draft: Boolean = false, val prerelease: Boolean = false,
    val assets: List<AppReleaseAsset> = emptyList(),
) {
    fun isOfficial(): Boolean {
        val destination = url.toHttpUrlOrNull() ?: return false
        return !draft && destination.isOfficialGithubUrl() &&
            destination.pathSegments == listOf("nobYoQ", "novelia", "releases", "tag", tag)
    }

    val versionTag get() = tag.removePrefix("eink-")
    fun availableFor(current: String): Boolean =
        isOfficial() && !prerelease && isNewerAppVersion(current, versionTag)

    /** 按设备的 ABI 优先级选择，其次通用包；兼容早期未标注 ABI 的单 APK 发行。 */
    fun apkFor(supportedAbis: List<String>, einkOnly: Boolean = false): AppReleaseAsset? {
        if(!isOfficial()) return null
        val apks = assets.filter { it.isDownloadableApk(tag) && EINK_ASSET.containsMatchIn(it.name) == einkOnly }
        supportedAbis.forEach { abi -> apks.firstOrNull { it.abi == abi }?.let { return it } }
        return apks.firstOrNull { it.abi == "universal" }
            ?: apks.filter { it.abi == null }.singleOrNull()
    }
}

@Serializable data class AppReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long = 0,
    val state: String = "",
    val id: Long = 0,
    @SerialName("updated_at") val updatedAt: String = "",
    val digest: String? = null,
) {
    internal val abi: String? get() = APK_ABI.find(name)?.value?.lowercase()
    val identity: String get() = "$downloadUrl|$id|$updatedAt|$size|${digest.orEmpty()}"
    val sha256: String? get() = digest?.removePrefix("sha256:")?.lowercase()
        ?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }

    internal fun isOfficialFor(tag: String): Boolean {
        val destination = downloadUrl.toHttpUrlOrNull() ?: return false
        return state == "uploaded" && size > 0 && name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]*")) &&
            destination.isOfficialGithubUrl() &&
            destination.pathSegments == listOf("nobYoQ", "novelia", "releases", "download", tag, name)
    }

    internal fun isDownloadableApk(tag: String): Boolean = isOfficialFor(tag) &&
        name.endsWith(".apk", ignoreCase = true) && !NON_DISTRIBUTION_APK.containsMatchIn(name)
}

private val APK_ABI = Regex("(?<![a-z0-9])(?:arm64-v8a|armeabi-v7a|x86_64|x86|universal)(?![a-z0-9])", RegexOption.IGNORE_CASE)
private val NON_DISTRIBUTION_APK = Regex("(?:^|[-_.])(?:staging|unsigned|unaligned|debug|androidtest)(?:[-_.]|$)", RegexOption.IGNORE_CASE)
private val EINK_ASSET = Regex("(?:^|[-_.])eink(?:[-_.]|$)", RegexOption.IGNORE_CASE)
private fun okhttp3.HttpUrl.isOfficialGithubUrl(): Boolean =
    scheme == "https" && host == "github.com" && port == 443 && username.isEmpty() && password.isEmpty() &&
        query == null && fragment == null

/** 独立公开客户端，不携带原站账号、镜像口令或 Cookie。 */
class AppReleaseClient(
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build(),
    private val endpoint: String = LATEST_RELEASE_API,
    previewEndpoint: String? = null,
    private val einkOnly: Boolean = BuildConfig.EINK_ONLY,
) {
    private val previewUrl = previewEndpoint ?: if (einkOnly) "$PREVIEW_RELEASE_API-eink" else PREVIEW_RELEASE_API
    suspend fun latest(): AppRelease? {
        if (!einkOnly) return fetch<AppRelease>(endpoint)?.takeIf {
            it.isOfficial() && !it.prerelease && ReleaseVersion.parse(it.tag) != null
        }
        // 电子纸版单独发布；普通版提前发版时，仍可找到上一份电子纸更新。
        val releases = endpoint.substringBeforeLast('/')
        var page = 1
        while (true) {
            val batch = fetch<List<AppRelease>>("$releases?per_page=30&page=$page") ?: return null
            batch.firstOrNull { it.isOfficial() && !it.prerelease && it.tag.startsWith("eink-v") &&
                ReleaseVersion.parse(it.versionTag) != null }?.let { return it }
            if (batch.size < 30) return null
            page++
        }
    }

    suspend fun latestForDevice(abis: List<String>): AppRelease? = latest()?.takeIf { it.apkFor(abis, einkOnly) != null }

    suspend fun preview(): AppRelease? = fetch<AppRelease>(previewUrl)?.takeIf {
        it.prerelease && it.isOfficial() && it.tag == if (einkOnly) "preview-eink" else "preview"
    }

    suspend fun candidate(channel: AppReleaseChannel, abis: List<String>, currentVersion: String? = null): AppDownloadCandidate? {
        val release = (if(channel == AppReleaseChannel.Preview) preview() else latest())
            ?: throw AppReleaseException("暂时没有可用的${if(channel == AppReleaseChannel.Preview) "预览版" else "正式版"}")
        if(!release.isOfficial() || release.prerelease != (channel == AppReleaseChannel.Preview))
            throw AppReleaseException("发行版信息无效，请稍后重试")
        if(channel == AppReleaseChannel.Stable && currentVersion != null && !isNewerAppVersion(currentVersion, release.versionTag)) return null
        val apk = release.apkFor(abis, einkOnly) ?: throw AppReleaseException("此发行版暂未提供适合当前设备和应用版本的 APK")
        if(channel == AppReleaseChannel.Stable)
            return AppDownloadCandidate(channel, release.versionTag.removePrefix("v").removePrefix("V"), apk)
        val infoName = if (einkOnly) "Novelia-eink-preview-build-info.txt" else "Novelia-preview-build-info.txt"
        val info = release.assets.singleOrNull { it.name == infoName && it.isOfficialFor(release.tag) }
            ?: throw AppReleaseException("预览包缺少构建信息，暂时无法检查版本")
        val response = client.newNetworkCall(Request.Builder().url(info.downloadUrl).build()).awaitText()
        if(response.code !in 200..299 || response.text.length > 16_384)
            throw AppReleaseException("获取预览构建信息失败，请稍后重试")
        return parsePreviewCandidate(apk, response.text)
    }

    private suspend inline fun <reified T> fetch(url: String): T? {
        val request = Request.Builder().url(url).header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "Novelia-Android").build()
        val response = client.newNetworkCall(request).awaitText()
        if(response.code == 404) return null
        if(response.code !in 200..299) throw AppReleaseException("获取发行版失败（HTTP ${response.code}），请稍后重试")
        return appJson.decodeFromString<T>(response.text)
    }
}
