package cc.novelia.app.data.auth

import cc.novelia.app.data.network.newNetworkCall

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

/** 小说和论坛使用独立应用令牌，并按原站／镜像分别加密保存；线路变化使旧请求失效。 */
class Session(context: Context, private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).build(),
    sources: BookSources? = null,
    override val target: AuthTarget = AuthTarget.NOVEL,
) : ApiSession {
    constructor(context: Context, authTarget: AuthTarget,
        client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).build(),
    ) : this(context, client, target = authTarget)

    private val followsBookSources = target == AuthTarget.NOVEL || sources != null
    // 独立构造的论坛会话默认原站；应用装配时显式传入共同的线路选择。
    private val sources = sources ?: if(target == AuthTarget.NOVEL) BookSources.load(context) else BookSources()
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
    private fun bindingSource(selection: SourceSelection) = if(target == AuthTarget.FORUM)
        if(selection.source == BookSource.ORIGINAL) "forum" else "forum-${selection.source.id}"
        else selection.source.id
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
        if(!followsBookSources) request else
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
    private fun acceptCookies(source: BookSource, url: String, headers: List<String>, borrowedCookies: String? = null) {
        if(headers.isEmpty() && borrowedCookies == null) return
        if(source == BookSource.ORIGINAL) {
            headers.forEach { CookieManager.getInstance().setCookie(url, it) }
            CookieManager.getInstance().flush()
        } else {
            val saved = MirrorAuthCookies(borrowedCookies ?: stored(source, "cookies")).accept(url.toHttpUrl(), headers)
            preferences.getValue(source).edit().putString("cookies", cipher.encrypt(saved)).apply()
        }
    }
    private suspend fun refreshRequest(binding: SessionBinding, allowAccountChange: Boolean,
        sharedAuth: Pair<Session, SessionBinding>? = null,
    ): Boolean {
        val context = currentCoroutineContext()
        context.ensureActive()
        val refresh = current(binding) { selected ->
            val url = "${selected.source.authOrigin}/api/v1/auth/refresh?app=${target.appId}"
            val ownCookie = cookie(selected.source, url)
            // 镜像 SSO Cookie 不进 WebView。仅从仍有效的同线路小说会话复用，
            // 成功后独立保存到论坛，供论坛令牌续期使用。
            val borrowedCookies = if(ownCookie == null && selected.source == BookSource.XKVI) sharedAuth?.let { (main, mainBinding) ->
                main.current(mainBinding) { mainSelection ->
                    if(mainSelection.source == selected.source) main.stored(selected.source, "cookies") else null
                }
            } else null
            val cookie = ownCookie ?: borrowedCookies?.let { MirrorAuthCookies(it).header(url.toHttpUrl()).ifEmpty { null } } ?: run {
                if(profile.value?.expiresAt?.let { it < System.currentTimeMillis() / 1000 } == true)
                    state.clear(local(binding)) { preferences.getValue(selected.source).edit().remove("value").apply() }
                return@current null
            }
            Request.Builder().url(url).header("Cookie", cookie)
                .apply { if(followsBookSources) tag(SourceSelection::class.java, selected) }
                .header("Origin", if(selected.source == BookSource.ORIGINAL) target.origin else selected.source.origin)
                .post(ByteArray(0).toRequestBody()).build() to borrowedCookies
        } ?: return false
        val (request, borrowedCookies) = refresh
        return client.newNetworkCall(request).awaitBody { response ->
            context.ensureActive()
            ensureCurrent(binding)
            if(!response.isSuccessful) {
                if(response.code == 401) current(binding) { selected ->
                    // 匿名认证失败不改变会话代次，避免同时进行的公开读取被无故作废。
                    if(state.tokenFor(local(binding)) != null)
                        state.clear(local(binding)) { preferences.getValue(selected.source).edit().remove("value").apply() }
                }
                return@awaitBody false
            }
            val value = response.body?.string()?.trim() ?: return@awaitBody false
            val user = runCatching { parse(value) }.getOrElse { throw ApiException(502, "认证服务未返回有效的登录令牌，请稍后重试") }
            context.ensureActive()
            val commit = {
                current(binding) { selected ->
                    state.commit(local(binding), value, user, allowAccountChange) {
                        preferences.getValue(selected.source).edit().putString("value", cipher.encrypt(value))
                            .remove(AUTO_LOGIN_DISABLED).apply()
                        acceptCookies(selected.source, request.url.toString(), response.headers.values("Set-Cookie"), borrowedCookies)
                    }
                }
            }
            if(sharedAuth == null) commit() else {
                val (main, mainBinding) = sharedAuth
                main.current(mainBinding) { mainSelection ->
                    // 共享 Cookie 可能属于另一账号；不能把它静默绑定到同线路已登录的小说账号。
                    if(mainSelection.source == sources.capture().source && mainBinding.account != null && mainBinding.account != user.username) false
                    else commit()
                }
            }
        }
    }
    /** 仅复用同线路已登录小说账号的认证；显式登录可解除论坛主动退出状态。 */
    suspend fun loginFromSharedAuth(main: Session, explicit: Boolean = false): Boolean {
        require(target == AuthTarget.FORUM && main.target == AuthTarget.NOVEL)
        val binding = capture()
        val mainBinding = main.capture()
        return withContext(Dispatchers.IO) { refreshLock.withLock {
            if(tokenFor(binding) != null) return@withLock true
            val selected = sources.capture()
            // 点击登录也不能仅凭残留 Cookie 恢复上一个账号；小说游客必须使用登录表单。
            if(mainBinding.source != selected.source.id || mainBinding.account == null) return@withLock false
            if(!explicit && preferences.getValue(selected.source).getBoolean(AUTO_LOGIN_DISABLED, false)) return@withLock false
            main.ensureCurrent(mainBinding)
            refreshRequest(binding, allowAccountChange = true, sharedAuth = main to mainBinding)
        } }
    }
    /** 使用当前来源的认证 Cookie 获取应用令牌。 */
    suspend fun refresh(): Boolean {
        val binding = capture()
        val previousToken = tokenFor(binding)
        return withContext(Dispatchers.IO) { refreshLock.withLock {
            val current = tokenFor(binding)
            if(current != previousToken) current != null else refreshRequest(binding, allowAccountChange = true)
        } }
    }
    /** 原站和镜像共用原生表单；所有请求复用应用传输（含原站 ECH）。 */
    suspend fun login(username: String, password: String, email: String? = null, otp: String? = null): Boolean {
        require(username.isNotBlank() && password.isNotEmpty()) { "请填写用户名或邮箱和密码" }
        val binding = capture()
        return withContext(Dispatchers.IO) { refreshLock.withLock {
            val value = authenticate(binding, if(email == null) AuthOperation.LOGIN else AuthOperation.REGISTER, buildJsonObject {
                put("app", target.appId); put("username", username.trim()); put("password", password)
                if(email != null) { put("email", email.trim()); put("otp", otp.orEmpty().trim()) }
            })
            currentCoroutineContext().ensureActive()
            val user = runCatching { parse(value) }.getOrNull()
            if(user != null) current(binding) { selected ->
                state.commit(local(binding), value, user, allowAccountChange = true) {
                    preferences.getValue(selected.source).edit().putString("value", cipher.encrypt(value)).remove(AUTO_LOGIN_DISABLED).apply()
                }
            } else refreshRequest(binding, allowAccountChange = true)
        } }
    }
    suspend fun requestOtp(email: String, resetPassword: Boolean = false) {
        require(email.isNotBlank()) { "请填写邮箱" }
        val binding = capture()
        withContext(Dispatchers.IO) {
            authenticate(binding, if(resetPassword) AuthOperation.RESET_OTP else AuthOperation.REGISTER_OTP, buildJsonObject {
                put("email", email.trim()); put("type", if(resetPassword) "reset_password" else "verify")
            })
        }
    }
    suspend fun resetPassword(email: String, password: String, otp: String) {
        require(email.isNotBlank() && password.isNotEmpty() && otp.isNotBlank()) { "请填写邮箱、新密码和验证码" }
        val binding = capture()
        withContext(Dispatchers.IO) {
            authenticate(binding, AuthOperation.RESET_PASSWORD, buildJsonObject {
                put("email", email.trim()); put("password", password); put("otp", otp.trim())
            })
        }
    }
    private suspend fun authenticate(binding: SessionBinding, operation: AuthOperation, payload: JsonObject): String {
        val request = current(binding) { selected ->
            val url = "${selected.source.authOrigin}/api/v1/auth/${operation.path}"
            Request.Builder().url(url)
                .apply { if(followsBookSources) tag(SourceSelection::class.java, selected) }
                .header("Origin", if(selected.source == BookSource.ORIGINAL) target.origin else selected.source.origin)
                .apply { cookie(selected.source, url)?.let { header("Cookie", it) } }
                .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        }
        val context = currentCoroutineContext()
        return client.newNetworkCall(request).awaitBody { response ->
            context.ensureActive()
            ensureCurrent(binding)
            if(!response.isSuccessful) {
                // 响应读取在会话锁外；读取期间换号、切线或取消后不能显示旧错误。
                val error = authenticationError(operation, response)
                context.ensureActive()
                current(binding) { throw error }
            }
            val value = response.body?.string()?.trim().orEmpty()
            context.ensureActive()
            current(binding) { selected ->
                acceptCookies(selected.source, request.url.toString(), response.headers.values("Set-Cookie"))
                value
            }
        }
    }
    /** 主站退出同时清除同线路论坛会话，使用共同线路锁防止清理途中切换来源。 */
    suspend fun logoutWithForum(forum: Session) {
        require(target == AuthTarget.NOVEL && forum.target == AuthTarget.FORUM && sources === forum.sources)
        val binding = capture()
        withContext(Dispatchers.IO) { current(binding) {
            clear()
            forum.clear()
        } }
    }
    // 单独退出论坛仍保留小说会话；共享 Cookie 只有在同线路小说已登录时才能用于论坛登录。
    // clear 会立即使当前会话的旧登录、刷新请求失效。
    suspend fun logout() = withContext(Dispatchers.IO) { clear() }
    fun clear() { val selected = sources.capture(); sources.withSelection(selected) { state.clear {
        preferences.getValue(selected.source).edit().clear()
            .apply { if(target == AuthTarget.FORUM) putBoolean(AUTO_LOGIN_DISABLED, true) }.apply()
    } } }
    companion object {
        const val AUTH_URL = "https://auth.novelia.cc"
        private const val AUTO_LOGIN_DISABLED = "auto-login-disabled"
    }
}
