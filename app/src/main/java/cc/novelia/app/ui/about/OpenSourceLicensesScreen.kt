package cc.novelia.app.ui.about

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.novelia.app.ui.account.ProfileDetailList
import cc.novelia.app.ui.account.ProfileDetailCard
import cc.novelia.app.ui.account.ProfileDetailScreen
import cc.novelia.app.ui.navigation.AppController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun OpenSourceLicensesScreen(c: AppController) {
    val paragraphs by produceState<List<String>?>(null, c.app) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                c.app.assets.open("open-source/NOTICE.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
                    .split(Regex("\\n\\s*\\n"))
                    .filter { it.isNotBlank() }
            }.getOrElse { listOf("无法读取许可证，请从项目源码仓库查看 LICENSE、NOTICE.md 与 licenses 目录。") }
        }
    }
    ProfileDetailScreen("开源许可证", c::back) { padding ->
        ProfileDetailList(Modifier.padding(padding)) {
            if (paragraphs == null) item { Text("正在读取许可证…", Modifier.padding(20.dp)) }
            items(paragraphs.orEmpty()) { paragraph ->
                ProfileDetailCard { SelectionContainer {
                    Text(paragraph, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
                } }
            }
        }
    }
}
