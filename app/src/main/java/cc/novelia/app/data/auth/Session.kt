package cc.novelia.app.data.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.CookieManager
import cc.novelia.app.data.model.Profile
import cc.novelia.app.data.network.awaitBody
import cc.novelia.app.data.storage.appJson
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class Session(context: Context) : AuthenticationSession {
    private val preferences = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("novelia.session", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("novelia.session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private val initialToken: String? = runCatching { preferences.getString("value", null)?.let { encrypted ->
        val bytes = Base64.decode(encrypted, Base64.NO_WRAP)
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    } }.getOrNull()
    private val state = SessionState(initialToken, initialToken?.let { runCatching { parse(it) }.getOrNull() })
    val token: String? get() = state.token
    val profile = state.profile
    private val refreshLock = Mutex()
    private val client = OkHttpClient.Builder().followRedirects(false).build()
    private fun parse(value: String): Profile {
        val payload = appJson.parseToJsonElement(Base64.decode(value.split('.')[1], Base64.URL_SAFE or Base64.NO_WRAP).toString(Charsets.UTF_8)).jsonObject
        return Profile(payload.getValue("sub").jsonPrimitive.content, payload.getValue("role").jsonPrimitive.content, payload.getValue("crat").jsonPrimitive.long, payload.getValue("exp").jsonPrimitive.long)
    }
    override fun capture(): SessionBinding = state.capture()
    override fun tokenFor(binding: SessionBinding): String? = state.tokenFor(binding)
    override suspend fun refreshIfCurrent(binding: SessionBinding, previousToken: String?): Boolean = refreshLock.withLock {
        val current = tokenFor(binding)
        if (current != previousToken) return@withLock current != null
        refreshRequest(binding, allowAccountChange = false)
    }
    private suspend fun refreshRequest(binding: SessionBinding, allowAccountChange: Boolean): Boolean {
        val requestContext = currentCoroutineContext()
        requestContext.ensureActive()
        ensureCurrent(binding)
        val cookie = CookieManager.getInstance().getCookie(AUTH_URL) ?: run {
            if(profile.value?.expiresAt?.let { it < System.currentTimeMillis() / 1000 } == true) state.clear(binding) { preferences.edit().clear().apply() }
            return false
        }
        val request = Request.Builder().url("$AUTH_URL/api/v1/auth/refresh?app=n").header("Cookie", cookie).header("Origin", "https://n.novelia.cc").post(ByteArray(0).toRequestBody()).build()
        return client.newCall(request).awaitBody { response ->
            requestContext.ensureActive()
            ensureCurrent(binding)
            if (!response.isSuccessful) { if (response.code == 401) state.clear(binding) { preferences.edit().clear().apply() }; return@awaitBody false }
            val value = response.body?.string()?.trim() ?: return@awaitBody false
            val user = parse(value)
            requestContext.ensureActive()
            state.commit(binding, value, user, allowAccountChange) {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
                preferences.edit().putString("value", Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)).apply()
                response.headers.values("Set-Cookie").forEach { CookieManager.getInstance().setCookie(AUTH_URL, it) }
                CookieManager.getInstance().flush()
            }
        }
    }
    suspend fun refresh(): Boolean {
        val binding = capture()
        val previousToken = tokenFor(binding)
        return withContext(Dispatchers.IO) { refreshLock.withLock {
            val current = tokenFor(binding)
            if (current != previousToken) current != null else refreshRequest(binding, allowAccountChange = true)
        } }
    }
    suspend fun logout() = withContext(Dispatchers.IO) {
        var cookie: String? = null
        // Invalidate refreshes immediately. A slow logout response must never clear a later login.
        state.clear {
            cookie = CookieManager.getInstance().getCookie(AUTH_URL)
            preferences.edit().clear().apply()
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
        cookie?.let {
            client.newCall(Request.Builder().url("$AUTH_URL/api/v1/auth/logout").header("Cookie", it).post(ByteArray(0).toRequestBody()).build()).awaitBody { }
        }
    }
    fun clear() { state.clear { preferences.edit().clear().apply() } }
    companion object { const val AUTH_URL = "https://auth.novelia.cc" }
}
