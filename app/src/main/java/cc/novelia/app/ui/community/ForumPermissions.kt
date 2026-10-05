package cc.novelia.app.ui.community

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import cc.novelia.app.data.model.ForumRules
import java.time.Instant
import kotlinx.coroutines.delay

/** Recompose at the deadline even when the user leaves the same post or comment open. */
@Composable internal fun rememberForumModificationTime(createdAt: Long): Long {
    val now by produceState(Instant.now().epochSecond, createdAt) {
        val remaining = createdAt + ForumRules.MODIFICATION_SECONDS - Instant.now().epochSecond
        if(remaining > 0) delay(remaining * 1000)
        value = Instant.now().epochSecond
    }
    return now
}
