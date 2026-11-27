package cc.novelia.app.ui.discover

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.components.base.AppTextButton

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun DiscoverFilterHelp(onAssistant: (() -> Unit)?, onSyntax: () -> Unit) {
    HorizontalDivider(Modifier.padding(top = 12.dp))
    FlowRow(Modifier.fillMaxWidth().testTag("discover-filter-help"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if(onAssistant != null) AppTextButton(onClick = onAssistant) {
            Icon(Icons.AutoMirrored.Outlined.ManageSearch, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("辅助搜索")
        }
        AppTextButton(onClick = onSyntax) {
            Icon(Icons.Outlined.Code, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("搜索语法")
        }
    }
}
