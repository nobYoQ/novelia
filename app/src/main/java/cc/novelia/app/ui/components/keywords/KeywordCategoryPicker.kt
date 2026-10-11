package cc.novelia.app.ui.components.keywords

import cc.novelia.app.ui.components.base.AppOutlinedButton
import cc.novelia.app.ui.theme.appShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.components.base.AppSelectionChip
import cc.novelia.app.ui.components.base.ChipSpacing
import cc.novelia.app.ui.components.base.appHorizontalScroll

@Composable fun KeywordCategoryChips(categories: List<String>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 4.dp)) {
    Row(modifier.appHorizontalScroll(rememberScrollState()).padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(ChipSpacing)) {
        categories.forEach { name ->
            key(name) {
                AppSelectionChip(name == selected, { onSelect(name) },
                    label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, shape = appShape(CircleShape),
                    modifier = Modifier.widthIn(max = 240.dp).testTag("keyword-category-$name"))
            }
        }
    }
}

@Composable fun KeywordCategoryPicker(categories: List<String>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        AppOutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("分类：$selected", Modifier.weight(1f))
            Icon(Icons.Outlined.ExpandMore, null)
        }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            categories.forEach { name ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(name); expanded = false },
                    trailingIcon = { if(name == selected) Icon(Icons.Outlined.Check, "已选") }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
    }
}
