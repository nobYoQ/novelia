package cc.novelia.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.novelia.app.data.webdav.WebDavConfig
import cc.novelia.app.ui.components.AppLazyColumn
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.theme.motionClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

@Composable
fun WebDavServerScreen(c: AppController) {
    val config by c.app.webDavConfig.config.collectAsStateWithLifecycle()
    val status by c.app.webDav.status.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val entry = remember { c.nav.currentBackStackEntry }
    WebDavServerForm(config, busy || status.running, error, c::back,
        onSave = { draft, password ->
            if(!busy) {
                busy = true
                error = null
                c.action {
                    try {
                        withContext(Dispatchers.IO) { c.app.webDavConfig.save(draft, password.takeIf(String::isNotEmpty)) }
                        c.app.webDav.testConnection()
                        c.message("配置已保存，连接测试通过")
                        if(c.nav.currentBackStackEntry == entry) c.back()
                    } catch(cancelled: CancellationException) {
                        throw cancelled
                    } catch(failure: Exception) {
                        error = failure.message ?: "连接测试未完成，请修改后重试"
                        throw failure
                    } finally { busy = false }
                }
            }
        },
        onClearPassword = {
            if(!busy) {
                busy = true
                c.action("已清除保存的密码") {
                    try { withContext(Dispatchers.IO) { c.app.webDavConfig.save(config, password = "") } }
                    finally { busy = false }
                }
            }
        },
    )
}

@Composable
internal fun WebDavServerForm(
    config: WebDavConfig,
    working: Boolean,
    error: String? = null,
    onBack: () -> Unit,
    onSave: (WebDavConfig, String) -> Unit,
    onClearPassword: () -> Unit,
) {
    var draft by remember { mutableStateOf(config) }
    // 凭据仅停留在内存，不进入界面恢复状态或备份。
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    WebDavPage("同步服务器", onBack, if(working) "正在测试连接…" else "保存并测试", Icons.Outlined.CloudDone,
        !working && draft.endpoint.isNotBlank(), working, { onSave(draft, password) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            AppLazyColumn(Modifier.widthIn(max = 640.dp).fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item { WebDavHero("连接 WebDAV", "填写云盘或服务器提供的连接信息。", Icons.Outlined.Dns) }
                item { WebDavCard {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        OutlinedTextField(
                            draft.endpoint, { draft = draft.copy(endpoint = it) },
                            label = { Text("服务器地址") }, placeholder = { Text("https://example.com/dav/") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                            singleLine = true, enabled = !working, modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            draft.username, { draft = draft.copy(username = it) }, label = { Text("用户名") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            singleLine = true, enabled = !working, modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            password, { password = it }, label = { Text("密码或应用授权码") },
                            visualTransformation = if(passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            trailingIcon = { IconButton(onClick = { passwordVisible = !passwordVisible }, enabled = !working) {
                                Icon(if(passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if(passwordVisible) "隐藏密码" else "显示密码")
                            } },
                            singleLine = true, enabled = !working, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } }
                item { Text("密码仅保存在此设备。留空可保留已保存的密码；更改服务器、账号或同步目录后，请重新填写。",
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                error?.let { value -> item { WebDavCard {
                    Text(value, Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                } } }
                item { WebDavRow("更多选项", "同步目录与此设备名称", Icons.Outlined.Tune,
                    modifier = Modifier.motionClickable { advanced = !advanced }) {
                    Icon(if(advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                } }
                if(advanced) item { WebDavCard {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        OutlinedTextField(draft.folder, { draft = draft.copy(folder = it) }, label = { Text("同步目录") },
                            supportingText = { Text("相对于服务器地址的目录，所有设备请填写同一目录") },
                            singleLine = true, enabled = !working, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(draft.deviceName, { draft = draft.copy(deviceName = it) }, label = { Text("此设备名称") },
                            singleLine = true, enabled = !working, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = { password = ""; onClearPassword() }, enabled = !working) { Text("清除已保存的密码") }
                    }
                } }
            }
        }
    }
}
