package cc.novelia.app.data.network

import cc.novelia.app.data.auth.ApiSession
import cc.novelia.app.data.auth.AuthTarget
import cc.novelia.app.data.model.ForumPage
import cc.novelia.app.data.model.ForumStrike

/** Strikes belong to the auth service, authenticated with this forum's access token. */
class ForumAccountApi(private val http: NoveliaApi) {
    init { require(http.session == null || (http.session as? ApiSession)?.target == AuthTarget.FORUM) }
    suspend fun strikes(page: Int): ForumPage<ForumStrike> {
        require(page >= 0 && page < Int.MAX_VALUE)
        return http.get("me/strikes", mapOf("page" to (page + 1).toString(), "page_size" to "20"))
    }
    companion object { const val BASE_URL = "https://auth.novelia.cc/api/v1/" }
}
