package cc.novelia.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Session(context: Context) {
    private val preferences = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("novelia.session", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("novelia.session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Volatile var token: String? = runCatching { preferences.getString("value", null)?.let { encrypted ->
        val bytes = Base64.decode(encrypted, Base64.NO_WRAP)
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    } }.getOrNull(); private set
    private val mutable = MutableStateFlow(token?.let { runCatching { parse(it) }.getOrNull() })
    val profile = mutable.asStateFlow()
    private val client = OkHttpClient.Builder().followRedirects(false).build()
    private fun parse(value: String): Profile {
        val payload = appJson.parseToJsonElement(Base64.decode(value.split('.')[1], Base64.URL_SAFE or Base64.NO_WRAP).toString(Charsets.UTF_8)).jsonObject
        return Profile(payload.getValue("sub").jsonPrimitive.content, payload.getValue("role").jsonPrimitive.content, payload.getValue("crat").jsonPrimitive.long, payload.getValue("exp").jsonPrimitive.long)
    }
    @Synchronized fun refreshBlocking(): Boolean {
        val cookie = CookieManager.getInstance().getCookie(AUTH_URL) ?: run { if(mutable.value?.expiresAt?.let { it < System.currentTimeMillis() / 1000 } == true) clear(); return false }
        val request = Request.Builder().url("$AUTH_URL/api/v1/auth/refresh?app=n").header("Cookie", cookie).header("Origin", "https://n.novelia.cc").post(ByteArray(0).toRequestBody()).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) { if (response.code == 401) clear(); return false }
            val value = response.body?.string()?.trim() ?: return false
            val user = parse(value)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            preferences.edit().putString("value", Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)).apply()
            response.headers.values("Set-Cookie").forEach { CookieManager.getInstance().setCookie(AUTH_URL, it) }
            CookieManager.getInstance().flush()
            token = value; mutable.value = user
            return true
        }
    }
    suspend fun refresh() = withContext(Dispatchers.IO) { refreshBlocking() }
    suspend fun logout() = withContext(Dispatchers.IO) {
        try {
            CookieManager.getInstance().getCookie(AUTH_URL)?.let { cookie ->
                client.newCall(Request.Builder().url("$AUTH_URL/api/v1/auth/logout").header("Cookie", cookie).post(ByteArray(0).toRequestBody()).build()).execute().close()
            }
        } finally { clear(); CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush() }
    }
    fun clear() { token = null; mutable.value = null; preferences.edit().clear().apply() }
    companion object { const val AUTH_URL = "https://auth.novelia.cc" }
}
