package cc.novelia.app.ui.community

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import cc.novelia.app.data.catalog.ForumLinks
import cc.novelia.app.ui.navigation.AppController

@Composable internal fun ForumRulesReminder(c: AppController) {
    TextButton(onClick = { c.openMarkdownLink("${ForumLinks.ORIGIN}/rules") }) {
        Text("发言请遵守《社区守则》")
    }
}
