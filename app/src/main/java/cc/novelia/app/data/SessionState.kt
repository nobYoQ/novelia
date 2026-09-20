package cc.novelia.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** An account can sign out and back in with the same name; generation still distinguishes it. */
data class SessionBinding(val account: String?, val generation: Long)

class SessionChangedException : ApiException(401, "登录账号已变化，请重新操作")

interface AuthenticationSession {
    fun capture(): SessionBinding
    fun tokenFor(binding: SessionBinding): String?
    fun ensureCurrent(binding: SessionBinding) { tokenFor(binding) }
    suspend fun refreshIfCurrent(binding: SessionBinding, previousToken: String?): Boolean
}

/** Keeps refresh commits and sign-out atomic without holding a lock during network IO. */
internal class SessionState(initialToken: String? = null, initialProfile: Profile? = null) {
    private var generation = 0L
    private var accessToken = initialToken.takeIf { initialProfile != null }
    private val mutable = MutableStateFlow(initialProfile)
    val profile = mutable.asStateFlow()
    val token: String? get() = synchronized(this) { accessToken }

    @Synchronized fun capture() = SessionBinding(mutable.value?.username, generation)
    @Synchronized fun tokenFor(binding: SessionBinding): String? {
        if (binding != capture()) throw SessionChangedException()
        return accessToken
    }

    @Synchronized fun commit(binding: SessionBinding, token: String, profile: Profile,
        allowAccountChange: Boolean, persist: () -> Unit = {}): Boolean {
        if (binding != capture() || (!allowAccountChange && binding.account != profile.username)) return false
        persist()
        if (binding.account != profile.username) generation++
        accessToken = token
        mutable.value = profile
        return true
    }

    @Synchronized fun clear(binding: SessionBinding? = null, persist: () -> Unit = {}): Boolean {
        if (binding != null && binding != capture()) return false
        generation++
        accessToken = null
        mutable.value = null
        persist()
        return true
    }
}
