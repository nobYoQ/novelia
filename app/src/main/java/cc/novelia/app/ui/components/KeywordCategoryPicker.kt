package cc.novelia.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable fun KeywordCategoryPicker(categories: List<String>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
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
