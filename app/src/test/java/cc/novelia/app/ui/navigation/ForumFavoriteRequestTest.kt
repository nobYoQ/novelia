package cc.novelia.app.ui.navigation

import cc.novelia.app.data.auth.SessionBinding
import cc.novelia.app.data.storage.appJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ForumFavoriteRequestTest {
    @Test fun restoredIntentRemainsBoundToTheAccountAndLoginThatCompletedAuthentication() {
        val binding = SessionBinding("账号 A", 3, source = "forum")
        val request = ForumFavoriteRequest(5, binding.account, binding.generation)
        val restored = appJson.decodeFromString<ForumFavoriteRequest>(appJson.encodeToString(request))
        assertTrue(restored.matches(5, binding))
        assertFalse(restored.matches(6, binding))
        assertFalse(restored.matches(5, binding.copy(account = "账号 B")))
        assertFalse(restored.matches(5, binding.copy(generation = 4)))
        assertFalse(restored.matches(5, binding.copy(account = null)))
    }

    @Test fun anAnonymousContinuationCannotSubmitACloudFavorite() {
        assertFalse(ForumFavoriteRequest(5, null, 0).matches(5, SessionBinding(null, 0, source = "forum")))
    }
}
