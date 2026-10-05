package cc.novelia.app.data.network

import cc.novelia.app.data.auth.ApiSession
import cc.novelia.app.data.auth.AuthTarget
import cc.novelia.app.data.auth.SessionBinding
import cc.novelia.app.data.model.ForumAttentionStatus
import cc.novelia.app.data.model.ForumStrikePage
import cc.novelia.app.data.model.ForumStrikeReadState
import cc.novelia.app.data.storage.appJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/** Strikes belong to the auth service, authenticated with this forum's access token. */
class ForumAccountApi(private val http: NoveliaApi) {
    init { require(http.session == null || (http.session as? ApiSession)?.target == AuthTarget.FORUM) }
    suspend fun strikes(page: Int, binding: SessionBinding? = http.session?.capture()): ForumStrikePage {
        require(page >= 0 && page < Int.MAX_VALUE)
        return decode(http.request("GET", "me/strikes", params = mapOf("page" to (page + 1).toString(), "page_size" to "20"), binding = binding))
    }
    suspend fun attentionStatus(binding: SessionBinding? = http.session?.capture()): ForumAttentionStatus =
        decode(http.request("GET", "me/attention-status", binding = binding))
    /** 仅确认已成功展示的列表快照；始终使用加载该快照时的会话，不捕获新的账号。 */
    suspend fun markStrikesRead(throughId: Long, binding: SessionBinding? = http.session?.capture()): ForumStrikeReadState {
        require(throughId >= 0)
        return decode(http.request("PUT", "me/strikes/read-state", appJson.encodeToString(mapOf("throughId" to throughId)), binding = binding))
    }
    private suspend inline fun <reified T> decode(raw: String): T = withContext(Dispatchers.Default) { appJson.decodeFromString(raw) }
    companion object { const val BASE_URL = "https://auth.novelia.cc/api/v1/" }
}
