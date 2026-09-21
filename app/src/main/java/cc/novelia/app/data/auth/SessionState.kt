package cc.novelia.app.data.auth

import cc.novelia.app.data.model.Profile
import cc.novelia.app.data.network.ApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 请求发起时捕获的会话身份。account 区分账号，generation 区分退出后重新登录等会话变化；
 * 即使用户名相同，旧请求也不能提交到新会话。这里不携带令牌，令牌由会话按绑定即时读取。
 */
data class SessionBinding(val account: String?, val generation: Long)

class SessionChangedException : ApiException(401, "登录账号已变化，请重新操作")

/** 网络层依赖的最小认证接口，允许测试替换真实 Keystore、Cookie 和刷新请求。 */
interface AuthenticationSession {
    fun capture(): SessionBinding
    fun tokenFor(binding: SessionBinding): String?
    fun ensureCurrent(binding: SessionBinding) { tokenFor(binding) }
    suspend fun refreshIfCurrent(binding: SessionBinding, previousToken: String?): Boolean
}

/**
 * 将刷新结果提交和退出登录串行化；网络请求在锁外执行，返回后再校验原会话绑定。
 * 所有代次、令牌和资料更新都经过同一监视器，避免旧刷新把已退出的账号重新写回来。
 */
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

    /**
     * 仅提交仍属于当前会话的结果。显式登录允许切换账号，自动续期要求账号保持一致。
     * persist 在发布新内存状态前执行；若持久化回调抛错，不会先发布尚未保存的新资料。
     */
    @Synchronized fun commit(binding: SessionBinding, token: String, profile: Profile,
        allowAccountChange: Boolean, persist: () -> Unit = {}): Boolean {
        if (binding != capture() || (!allowAccountChange && binding.account != profile.username)) return false
        persist()
        if (binding.account != profile.username) generation++
        accessToken = token
        mutable.value = profile
        return true
    }

    /** 带绑定的清除只能影响原会话；无绑定表示主动清除当前会话，并使所有旧绑定失效。 */
    @Synchronized fun clear(binding: SessionBinding? = null, persist: () -> Unit = {}): Boolean {
        if (binding != null && binding != capture()) return false
        generation++
        accessToken = null
        mutable.value = null
        persist()
        return true
    }
}
