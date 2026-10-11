package cc.novelia.app.ui.community

import cc.novelia.app.ui.components.base.AppTextButton
import cc.novelia.app.ui.components.base.AppIconButton
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.ui.navigation.AppController

@Composable internal fun ForumRulesReminder(c: AppController) {
    val state by c.store.state.collectAsStateWithLifecycle()
    ForumRulesNotice(!state.forumRulesReminderDismissed, { c.go("forum-rules") }) {
        c.store.update { it.copy(forumRulesReminderDismissed = true) }
    }
}

@Composable internal fun ForumRulesNotice(visible: Boolean, onOpen: () -> Unit, onDismiss: () -> Unit) {
    if(!visible) return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("forum-rules-notice")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppTextButton(onClick = onOpen, modifier = Modifier.weight(1f)) { Text("发言请遵守《社区守则》") }
            AppIconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "不再显示社区守则提示") }
        }
    }
}
