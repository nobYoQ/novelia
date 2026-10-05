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
        assertFalse(restored.matches(5, binding.copy(source = "forum-xkvi")))
        assertFalse(restored.matches(5, binding.copy(sourceRevision = 1)))
    }

    @Test fun mirrorContinuationKeepsSourceAndRevisionAcrossRestoration() {
        val binding = SessionBinding("账号 A", 3, source = "forum-xkvi", sourceRevision = 4)
        val request = ForumFavoriteRequest(5, binding.account, binding.generation, binding.source, binding.sourceRevision)
        val restored = appJson.decodeFromString<ForumFavoriteRequest>(appJson.encodeToString(request))
        assertTrue(restored.matches(5, binding))
        assertFalse(restored.matches(5, binding.copy(source = "forum")))
        assertFalse(restored.matches(5, binding.copy(sourceRevision = 6)))
        val legacy = appJson.decodeFromString<ForumFavoriteRequest>("""{"postId":5,"account":"账号 A","generation":3}""")
        assertFalse(legacy.matches(5, binding))
        assertTrue(legacy.matches(5, binding.copy(source = "forum", sourceRevision = 0)))
    }

    @Test fun anAnonymousContinuationCannotSubmitACloudFavorite() {
        assertFalse(ForumFavoriteRequest(5, null, 0).matches(5, SessionBinding(null, 0, source = "forum")))
    }
}
