package cc.novelia.app.ui.account

import cc.novelia.app.ui.components.base.AppButton
import cc.novelia.app.ui.components.base.AppTextButton
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.sync.CloudSyncWorker
import cc.novelia.app.ui.components.base.Screen
import cc.novelia.app.ui.components.base.friendlyMessage
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.navigation.finishLoginNavigation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable internal fun MirrorLoginScreen(c: AppController, forum: Boolean = false) {
    val session = if(forum) c.forumSession else c.session
    // 密码和验证码不使用 rememberSaveable，也不写入磁盘或日志。
    var register by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var cooldown by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    DisposableEffect(c) { onDispose { c.afterLogin = null } }
    LaunchedEffect(cooldown) { if(cooldown > 0) { delay(1000); cooldown-- } }
    Screen(if(register) "通过镜像注册" else if(forum) "通过镜像登录论坛" else "通过镜像登录", c::back) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("XKVI 反代镜像 · book.xkvi.top", style = MaterialTheme.typography.titleMedium)
            Text("使用 Novelia 账号，登录请求通过镜像转发。" + (if(forum) "论坛登录状态单独保存。" else "登录后可访问小说服务。"),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), enabled = !busy,
                label = { Text(if(register) "用户名" else "用户名或邮箱") }, singleLine = true)
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), enabled = !busy,
                label = { Text("密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            if(register) {
                OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), enabled = !busy && !sending,
                    label = { Text("邮箱") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                OutlinedTextField(otp, { otp = it }, Modifier.fillMaxWidth(), enabled = !busy,
                    label = { Text("邮箱验证码") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                AppTextButton(enabled = !busy && !sending && cooldown == 0 && email.isNotBlank(), onClick = {
                    if(!android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) { error = "请填写有效的邮箱"; return@AppTextButton }
                    sending = true; error = null; notice = null
                    scope.launch {
                        try { session.requestMirrorOtp(email); notice = "验证码已发送，请检查邮箱及垃圾箱"; cooldown = 60 }
                        catch(cancelled: CancellationException) { throw cancelled }
                        catch(e: Exception) { error = e.friendlyMessage(); cooldown = 60 }
                        finally { sending = false }
                    }
                }) { Text(if(sending) "正在发送…" else if(cooldown > 0) "${cooldown} 秒后重试" else "发送邮箱验证码") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
            AppButton(enabled = !busy && !sending && username.isNotBlank() && password.isNotEmpty(), modifier = Modifier.fillMaxWidth(), onClick = {
                if(register && (username.trim().length !in 2..16 || password.length !in 8..100 ||
                        !android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches() || !otp.trim().matches(Regex("[0-9]{6}")))) {
                    error = "请检查用户名（2–16 字符）、密码（8–100 字符）、邮箱和 6 位验证码"; return@AppButton
                }
                busy = true; error = null; notice = null
                scope.launch {
                    try {
                        if(session.loginMirror(username, password, email.takeIf { register }, otp.takeIf { register })) {
                            password = ""; otp = ""
                            if(!forum && c.store.state.value.autoSync) session.profile.value?.username?.let { CloudSyncWorker.enqueue(c.app, it) }
                            finishLoginNavigation(c, forum); c.message("已登录")
                        } else error = "认证成功，但未取得登录会话，请稍后重试"
                    } catch(cancelled: CancellationException) { throw cancelled }
                    catch(e: Exception) { error = e.friendlyMessage() }
                    finally { busy = false }
                }
            }) { Text(if(busy) "验证中…" else if(register) "注册并登录" else "登录") }
            AppTextButton(enabled = !busy && !sending, onClick = { register = !register; error = null; notice = null; password = ""; otp = "" }) {
                Text(if(register) "已有账号，去登录" else "没有账号，注册")
            }
            AppTextButton(enabled = !busy, onClick = { c.go("settings?section=NETWORK") }) { Text("切换书源线路") }
        }
    }
}
