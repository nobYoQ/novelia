package cc.novelia.app.ui.community

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import cc.novelia.app.data.community.ForumRules
import java.time.Instant
import kotlinx.coroutines.delay
import cc.novelia.app.data.model.Profile

/** Recompose at the deadline even when the user leaves the same post or comment open. */
@Composable internal fun rememberForumModificationTime(createdAt: Long, windowSeconds: Long): Long {
    val now by produceState(Instant.now().epochSecond, createdAt, windowSeconds) {
        value = Instant.now().epochSecond
        val remaining = createdAt + windowSeconds - value
        if(remaining > 0) delay(remaining * 1000)
        value = Instant.now().epochSecond
    }
    return now
}

/** 到注册满 30 天时自动更新发送按钮，不要求用户重新进入编辑器。 */
@Composable internal fun rememberForumCommentTime(profile: Profile?): Long {
    val now by produceState(Instant.now().epochSecond, profile?.createdAt) {
        value = Instant.now().epochSecond
        val remaining = profile?.let { ForumRules.COMMENT_ACCOUNT_AGE_SECONDS - (value - it.createdAt) } ?: 0
        if(remaining in 1..ForumRules.COMMENT_ACCOUNT_AGE_SECONDS) {
            delay(remaining * 1000)
            value = Instant.now().epochSecond
        }
    }
    return now
}
