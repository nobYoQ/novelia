package cc.novelia.app.data.auth

import android.content.Context
import android.util.Base64
import android.webkit.CookieManager
import cc.novelia.app.data.model.Profile
import cc.novelia.app.data.network.ApiException
import cc.novelia.app.data.network.BookSource
import cc.novelia.app.data.network.BookSources
import cc.novelia.app.data.network.SourceSelection
import cc.novelia.app.data.network.awaitBody
import cc.novelia.app.data.storage.appJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** 原站、镜像及论坛分别登录和加密保存；论坛会话不受小说书源切换影响。 */
class Session(context: Context, private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).build(),
    sources: BookSources? = null,
    override val target: AuthTarget = AuthTarget.NOVEL,
) : ApiSession {
    constructor(context: Context, authTarget: AuthTarget,
        client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).build(),
    ) : this(context, client, target = authTarget)

    // 论坛始终使用独立认证站，不订阅小说镜像的来源和代次变化。
    private val sources = if(target == AuthTarget.NOVEL) sources ?: BookSources.load(context) else BookSources()
    private val preferences = BookSource.entries.associateWith {
        context.getSharedPreferences(if(it == BookSource.ORIGINAL) target.preferencesName else "${target.preferencesName}-${it.id}", Context.MODE_PRIVATE)
    }
    private val cipher = DeviceCipher(if(target == AuthTarget.NOVEL) "novelia.session" else "novelia.forum.session")
    private fun stored(source: BookSource, key: String): String? = runCatching {
        preferences.getValue(source).getString(key, null)?.let(cipher::decrypt)
    }.getOrNull()
    private val state = SessionState()
    override val token: String? get() = sources.withCurrent { state.token }
    val profile = state.profile
    private val refreshLock = Mutex()
    init {
        this.sources.observe { selected ->
            val value = stored(selected.source, "value")
            state.replace(value, value?.let { runCatching { parse(it) }.getOrNull() })
        }
    }
    // JWT 解码只用于展示；签名和权限由服务端校验。
    private fun parse(value: String): Profile {
        val payload = appJson.parseToJsonElement(Base64.decode(value.split('.')[1], Base64.URL_SAFE or Base64.NO_WRAP).toString(Charsets.UTF_8)).jsonObject
        return Profile(payload.getValue("sub").jsonPrimitive.content, payload.getValue("role").jsonPrimitive.content,
            payload.getValue("crat").jsonPrimitive.long, payload.getValue("exp").jsonPrimitive.long,
            payload["uid"]?.jsonPrimitive?.longOrNull)
    }
    private fun bindingSource(selection: SourceSelection) = if(target == AuthTarget.FORUM) "forum" else selection.source.id
    override fun capture(): SessionBinding = sources.withCurrent { state.capture().copy(source = bindingSource(it), sourceRevision = it.revision) }
    private fun local(binding: SessionBinding) = binding.copy(source = "original", sourceRevision = 0)
    private fun <T> current(binding: SessionBinding, action: (SourceSelection) -> T): T {
        val selected = sources.capture()
        return sources.withSelection(selected) {
            if(binding.source != bindingSource(selected) || binding.sourceRevision != selected.revision) throw SessionChangedException()
            state.tokenFor(local(binding))
            action(selected)
        }
    }
    override fun tokenFor(binding: SessionBinding): String? = current(binding) { state.tokenFor(local(binding)) }
    override fun bindRequest(request: Request, binding: SessionBinding): Request = current(binding) {
        if(target == AuthTarget.FORUM) request else
            request.newBuilder().url(sources.route(request.url, it)).tag(SourceSelection::class.java, it).build()
    }
    override suspend fun refreshIfCurrent(binding: SessionBinding, previousToken: String?): Boolean = refreshLock.withLock {
        val current = tokenFor(binding)
        if(current != previousToken) return@withLock current != null
        // 已退出的会话不能通过共享 SSO Cookie 自动重新登录。
        if(current == null) return@withLock false
        refreshRequest(binding, allowAccountChange = false)
    }
    private fun cookie(source: BookSource, url: String): String? = if(source == BookSource.ORIGINAL)
        CookieManager.getInstance().getCookie(url) else MirrorAuthCookies(stored(source, "cookies")).header(url.toHttpUrl()).ifEmpty { null }
    private fun acceptCookies(source: BookSource, url: String, headers: List<String>) {
        if(headers.isEmpty()) return
        if(source == BookSource.ORIGINAL) {
            headers.forEach { CookieManager.getInstance().setCookie(url, it) }
            CookieManager.getInstance().flush()
        } else {
            val saved = MirrorAuthCookies(stored(source, "cookies")).accept(url.toHttpUrl(), headers)
            preferences.getValue(source).edit().putString("cookies", cipher.encrypt(saved)).apply()
        }
    }
    private suspend fun refreshRequest(binding: SessionBinding, allowAccountChange: Boolean): Boolean {
        val context = currentCoroutineContext()
        context.ensureActive()
        val request = current(binding) { selected ->
            val url = "${selected.source.authOrigin}/api/v1/auth/refresh?app=${target.appId}"
            val cookie = cookie(selected.source, url) ?: run {
                if(profile.value?.expiresAt?.let { it < System.currentTimeMillis() / 1000 } == true)
                    state.clear(local(binding)) { preferences.getValue(selected.source).edit().remove("value").apply() }
                return@current null
            }
            Request.Builder().url(url).header("Cookie", cookie)
                .apply { if(target == AuthTarget.NOVEL) tag(SourceSelection::class.java, selected) }
                .header("Origin", if(target == AuthTarget.FORUM) target.origin else selected.source.origin)
                .post(ByteArray(0).toRequestBody()).build()
        } ?: return false
        return client.newCall(request).awaitBody { response ->
            context.ensureActive()
            ensureCurrent(binding)
            if(!response.isSuccessful) {
                if(response.code == 401) current(binding) { selected ->
                    state.clear(local(binding)) { preferences.getValue(selected.source).edit().remove("value").apply() }
                }
                return@awaitBody false
            }
            val value = response.body?.string()?.trim() ?: return@awaitBody false
            val user = runCatching { parse(value) }.getOrElse { throw ApiException(502, "认证服务未返回有效的登录令牌，请稍后重试") }
            context.ensureActive()
            current(binding) { selected ->
                state.commit(local(binding), value, user, allowAccountChange) {
                    preferences.getValue(selected.source).edit().putString("value", cipher.encrypt(value)).apply()
                    acceptCookies(selected.source, request.url.toString(), response.headers.values("Set-Cookie"))
                }
            }
        }
    }
    /** 原站网页或镜像原生表单完成认证后，从当前来源获取应用令牌。 */
    suspend fun refresh(): Boolean {
        val binding = capture()
        val previousToken = tokenFor(binding)
        return withContext(Dispatchers.IO) { refreshLock.withLock {
            val current = tokenFor(binding)
            if(current != previousToken) current != null else refreshRequest(binding, allowAccountChange = true)
        } }
    }
    suspend fun loginMirror(username: String, password: String, email: String? = null, otp: String? = null): Boolean {
        require(username.isNotBlank() && password.isNotEmpty()) { "请填写用户名或邮箱和密码" }
        val binding = capture()
        return withContext(Dispatchers.IO) { refreshLock.withLock {
            val value = mirrorAuth(binding, if(email == null) "login" else "register", buildJsonObject {
                put("app", "n"); put("username", username.trim()); put("password", password)
                if(email != null) { put("email", email.trim()); put("otp", otp.orEmpty().trim()) }
            })
            currentCoroutineContext().ensureActive()
            val user = runCatching { parse(value) }.getOrNull()
            if(user != null) current(binding) { selected ->
                state.commit(local(binding), value, user, allowAccountChange = true) {
                    preferences.getValue(selected.source).edit().putString("value", cipher.encrypt(value)).apply()
                }
            } else refreshRequest(binding, allowAccountChange = true)
        } }
    }
    suspend fun requestMirrorOtp(email: String) = withContext(Dispatchers.IO) {
        require(email.isNotBlank()) { "请填写邮箱" }
        mirrorAuth(capture(), "otp/request", buildJsonObject { put("email", email.trim()); put("type", "verify") })
        Unit
    }
    private suspend fun mirrorAuth(binding: SessionBinding, path: String, payload: JsonObject): String {
        val request = current(binding) { selected ->
            check(target == AuthTarget.NOVEL && selected.source == BookSource.XKVI) { "书源已变化，请重新打开登录页" }
            val url = "${selected.source.authOrigin}/api/v1/auth/$path"
            Request.Builder().url(url).tag(SourceSelection::class.java, selected).header("Origin", selected.source.origin)
                .apply { cookie(selected.source, url)?.let { header("Cookie", it) } }
                .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        }
        val context = currentCoroutineContext()
        return client.newCall(request).awaitBody { response ->
            context.ensureActive()
            current(binding) { selected ->
                if(!response.isSuccessful) throw ApiException(response.code, when(response.code) {
                    400, 422 -> "填写的信息不正确，请检查用户名、邮箱或验证码"
                    401 -> "登录失败，请检查账号和密码"
                    403 -> "镜像拒绝访问，请稍后重试或切换原站"
                    409 -> "用户名或邮箱已被使用，请直接登录"
                    429 -> "操作过于频繁，请稍后重试"
                    502 -> "认证未完成，请检查填写的信息或稍后重试（502）"
                    else -> "镜像认证服务暂不可用（${response.code}），请稍后重试"
                })
            }
            val value = response.body?.string()?.trim().orEmpty()
            context.ensureActive()
            current(binding) { selected ->
                acceptCookies(selected.source, request.url.toString(), response.headers.values("Set-Cookie"))
                value
            }
        }
    }
    // 服务端只提供全局 SSO 退出；本地退出保留另一应用的访问令牌和刷新 Cookie，
    // 后续显式登录仍可复用 SSO。clear 会立即使当前会话的旧刷新失效。
    suspend fun logout() = withContext(Dispatchers.IO) { clear() }
    fun clear() { val selected = sources.capture(); sources.withSelection(selected) { state.clear { preferences.getValue(selected.source).edit().clear().apply() } } }
    companion object { const val AUTH_URL = "https://auth.novelia.cc" }
}
