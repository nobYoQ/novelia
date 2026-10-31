package cc.novelia.app.ui.account

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cc.novelia.app.data.network.SourceSelection
import cc.novelia.app.data.sync.CloudSyncWorker
import cc.novelia.app.ui.components.base.AppButton
import cc.novelia.app.ui.components.base.AppIconButton
import cc.novelia.app.ui.components.base.AppTextButton
import cc.novelia.app.ui.components.base.Screen
import cc.novelia.app.ui.components.base.friendlyMessage
import cc.novelia.app.ui.navigation.AppController
import cc.novelia.app.ui.navigation.finishLoginNavigation
import cc.novelia.app.ui.theme.appRoundedCornerShape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class LoginMode { LOGIN, REGISTER, RESET_PASSWORD }

@Composable internal fun LoginForm(c: AppController, selected: SourceSelection, forum: Boolean) {
    val session = if(forum) c.forumSession else c.session
    // 密码和验证码不使用 rememberSaveable，也不写入磁盘或日志。
    var mode by remember { mutableStateOf(LoginMode.LOGIN) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    var cooldownUntil by rememberSaveable { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var switching by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(forum) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val register = mode == LoginMode.REGISTER
    val resetting = mode == LoginMode.RESET_PASSWORD
    val enabled = !busy && !sending && !verifying && !switching && !restoring
    val cooldown = ((cooldownUntil - now + 999) / 1000).coerceIn(0, 60)
    val canSubmit = enabled && (if(resetting) email.isNotBlank() else username.isNotBlank()) && password.isNotEmpty()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(cooldownUntil) {
        do {
            now = SystemClock.elapsedRealtime()
            if(now >= cooldownUntil) break
            delay(1000)
        } while(true)
    }
    LaunchedEffect(session, selected) {
        if(forum) {
            val signedIn = try { session.loginFromSharedAuth(c.session, explicit = true) }
                catch(cancelled: CancellationException) { throw cancelled }
                catch(_: Exception) { false }
            try {
                // 前一页的自动登录可能已先完成，继续复用其有效会话。
                if(signedIn || session.profile.value != null) finishLoginNavigation(c, forum = true)
            } finally { restoring = false }
        }
    }
    fun changeMode(next: LoginMode) {
        focusManager.clearFocus()
        mode = next; password = ""; passwordVisible = false; otp = ""; error = null; notice = null
    }
    fun complete() {
        password = ""; otp = ""
        if(!forum && c.store.state.value.autoSync) session.profile.value?.username?.let { CloudSyncWorker.enqueue(c.app, it) }
        finishLoginNavigation(c, forum)
        c.message("已登录")
    }
    fun submit() {
        if(!canSubmit) return
        if(register || resetting) {
            if(password.length !in 8..100) { error = "密码应为 8–100 个字符"; return }
            if(!android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) { error = "请填写有效的邮箱"; return }
            if(resetting && otp.trim().length != 26) {
                error = "找回密码验证码应为 26 位字符，请完整复制邮件中的验证码"; return
            }
            if(register && !otp.trim().matches(Regex("[0-9]{6}"))) {
                error = "注册验证码应为 6 位数字，请检查邮件中的验证码"; return
            }
        }
        if(register && username.trim().length !in 2..16) { error = "用户名应为 2–16 字符"; return }
        keyboard?.hide()
        focusManager.clearFocus()
        busy = true; error = null; notice = null
        scope.launch {
            try {
                if(resetting) {
                    session.resetPassword(email, password, otp)
                    username = email.trim(); changeMode(LoginMode.LOGIN)
                    notice = "密码已重置，请使用新密码登录"
                } else if(session.login(username, password, email.takeIf { register }, otp.takeIf { register })) complete()
                else error = "认证成功，但未取得登录会话，请稍后重试"
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(e: Exception) { error = e.friendlyMessage() }
            finally { busy = false }
        }
    }

    if(verifying) TurnstileDialog(c.app.ech.client(), session.target, resetting,
        onDismiss = { verifying = false }, onVerified = { token ->
            verifying = false
            sending = true; error = null; notice = null
            val destination = email.trim()
            val passwordReset = resetting
            scope.launch {
                try {
                    session.requestOtp(destination, token, resetPassword = passwordReset)
                    notice = "验证码已发送，请检查邮箱及垃圾箱"
                    now = SystemClock.elapsedRealtime()
                    cooldownUntil = now + 60_000
                } catch(cancelled: CancellationException) { throw cancelled }
                catch(e: Exception) { error = e.friendlyMessage() }
                finally { sending = false }
            }
        })

    Screen(if(register) "注册 Novelia 账号" else if(resetting) "找回密码" else if(forum) "登录 Novelia 论坛" else "登录 Novelia", c::back) { padding ->
        LoginPageLayout(register, resetting, forum, Modifier.padding(padding).consumeWindowInsets(padding), source = {
            LoginSourcePicker(selected, forum, enabled, switching) { source ->
                if(source != selected.source) {
                    keyboard?.hide()
                    focusManager.clearFocus()
                    switching = true; error = null
                    scope.launch {
                        try { withContext(Dispatchers.IO) { c.app.bookSources.select(source) } }
                        catch(cancelled: CancellationException) { throw cancelled }
                        catch(e: Exception) { error = e.friendlyMessage() }
                        finally { switching = false }
                    }
                }
            }
        }) {
            if(restoring) LoginStatus("正在恢复登录状态…", loading = true)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                val next = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Next) })
                if(!resetting) LoginField(username, { username = it }, if(register) "用户名" else "用户名或邮箱",
                    Icons.Outlined.PersonOutline, "login-username", enabled, first = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), keyboardActions = next)
                LoginField(password, { password = it }, if(resetting) "新密码" else "密码",
                    Icons.Outlined.Lock, "login-password", enabled, first = resetting, last = !register && !resetting,
                    visualTransformation = if(passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password,
                        imeAction = if(register || resetting) ImeAction.Next else ImeAction.Done),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Next) }, onDone = { submit() }),
                    trailingIcon = {
                        AppIconButton(onClick = { passwordVisible = !passwordVisible }, enabled = enabled) {
                            Icon(if(passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                if(passwordVisible) "隐藏密码" else "显示密码", Modifier.size(22.dp))
                        }
                    })
                if(register || resetting) {
                    LoginField(email, { email = it }, "邮箱", Icons.Outlined.AlternateEmail, "login-email", enabled,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next), keyboardActions = next)
                    LoginField(otp, { otp = it }, if(resetting) "邮箱验证码（26 位字符）" else "邮箱验证码（6 位数字）",
                        Icons.Outlined.MarkEmailRead, "login-otp", enabled, last = true,
                        keyboardOptions = KeyboardOptions(keyboardType = if(resetting) KeyboardType.Ascii else KeyboardType.Number,
                            autoCorrectEnabled = false, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }))
                }
            }
            if(register || resetting) {
                AppTextButton(enabled = enabled && cooldown == 0L && email.isNotBlank(),
                    modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp).testTag("login-send-otp"),
                    onClick = {
                        if(!android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) {
                            error = "请填写有效的邮箱"; return@AppTextButton
                        }
                        keyboard?.hide()
                        focusManager.clearFocus()
                        error = null; notice = null; verifying = true
                    }) { Text(if(sending) "正在发送…" else if(cooldown > 0) "${cooldown} 秒后重试" else "发送邮箱验证码") }
            }
            error?.let { LoginStatus(it, error = true) }
            notice?.let { LoginStatus(it) }
            AppButton(enabled = canSubmit, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("login-submit"),
                shape = appRoundedCornerShape(24.dp), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp), onClick = ::submit) {
                if(busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                    Spacer(Modifier.width(10.dp))
                }
                Text(if(busy) "正在提交…" else if(register) "注册并登录" else if(resetting) "重置密码" else "登录",
                    Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(22.dp))
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stacked = maxWidth / LocalDensity.current.fontScale.coerceAtLeast(1f) < 300.dp
                val modes: @Composable () -> Unit = {
                    AppTextButton(enabled = enabled, modifier = Modifier.heightIn(min = 48.dp),
                        onClick = { changeMode(if(mode == LoginMode.LOGIN) LoginMode.REGISTER else LoginMode.LOGIN) }) {
                        Text(if(mode == LoginMode.LOGIN) "没有账号，注册" else "已有账号，去登录")
                    }
                    if(mode == LoginMode.LOGIN) AppTextButton(enabled = enabled, modifier = Modifier.heightIn(min = 48.dp),
                        onClick = { changeMode(LoginMode.RESET_PASSWORD) }) { Text("忘记密码？") }
                }
                if(stacked) Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { modes() }
                else Row(Modifier.fillMaxWidth(), horizontalArrangement = if(mode == LoginMode.LOGIN) Arrangement.SpaceBetween else Arrangement.Center) { modes() }
            }
        }
    }
}
