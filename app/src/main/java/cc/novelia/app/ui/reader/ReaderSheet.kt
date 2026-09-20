@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package cc.novelia.app.ui.reader

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import cc.novelia.app.ui.components.AppSheet

@Composable internal fun ReaderSheet(onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AppSheet(onDismissRequest, rememberModalBottomSheetState(skipPartiallyExpanded = true), content)
}
