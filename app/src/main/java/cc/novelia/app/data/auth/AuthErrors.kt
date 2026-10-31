package cc.novelia.app.data.auth

import cc.novelia.app.data.network.ApiException
import okhttp3.Response

internal enum class AuthOperation(val path: String) {
    LOGIN("login"),
    REGISTER("register"),
    REGISTER_OTP("otp/request"),
    RESET_OTP("otp/request"),
    RESET_PASSWORD("password/reset");

    val registration get() = this == REGISTER || this == REGISTER_OTP
    val passwordReset get() = this == RESET_PASSWORD || this == RESET_OTP
}

/** 只把已知的纯文本业务原因映射为本地提示，不回显账号、网关页面或服务端内部信息。 */
internal fun authenticationError(operation: AuthOperation, response: Response): ApiException {
    val reason = authErrorReason(response)
    val message = knownAuthError(operation, response.code, reason) ?: when(response.code) {
        400, 422 -> when(operation) {
            AuthOperation.LOGIN -> "登录信息不符合要求，请检查用户名或邮箱及密码"
            AuthOperation.REGISTER -> "注册信息不符合要求，请检查用户名、邮箱、密码和验证码"
            AuthOperation.REGISTER_OTP, AuthOperation.RESET_OTP -> "验证码请求未通过校验，请检查邮箱；仍失败时请前往网页完成操作"
            AuthOperation.RESET_PASSWORD -> "重置密码信息不符合要求，请检查邮箱、新密码和验证码"
        }
        401 -> when(operation) {
            AuthOperation.LOGIN -> "登录失败，请检查账号和密码"
            AuthOperation.REGISTER, AuthOperation.RESET_PASSWORD -> "验证未通过，请检查邮箱和验证码，必要时重新获取验证码"
            else -> "验证码请求未获授权，请前往网页完成操作（401）"
        }
        403 -> "认证请求被拒绝（403），请检查账号状态，或切换书源后重试"
        404 -> if(operation == AuthOperation.LOGIN || operation.passwordReset)
            "未找到账号或认证接口（404），请核对用户名或注册邮箱；信息无误时可切换书源重试"
            else "未找到认证接口（404），请切换书源或更新应用后重试"
        405 -> "当前线路不支持此认证操作（405），请切换书源或更新应用后重试"
        408, 504 -> "认证请求超时（${response.code}），请稍后重试或切换书源"
        409 -> if(operation.registration) "用户名或邮箱已被使用，请更换信息或使用已有账号登录"
            else "认证请求发生冲突（409），请返回后重新操作"
        429 -> "操作过于频繁，请稍后再试，避免连续提交"
        // 镜像可能把字段校验失败包装为 502，不能据此判断密码错误或服务宕机。
        502 -> "认证未完成（502），请检查填写的信息；仍失败时请切换书源或稍后重试"
        in 500..599 -> "认证服务暂时异常（${response.code}），请稍后重试或切换书源"
        in 300..399 -> "认证请求发生重定向（${response.code}），请切换书源或更新应用后重试"
        else -> "认证请求失败（${response.code}），请稍后重试；持续失败时请反馈此错误码"
    }
    return ApiException(response.code, message)
}

private fun knownAuthError(operation: AuthOperation, status: Int, reason: String?): String? = when(status) {
    400, 422 -> when(reason) {
        "无效验证码", "无效的验证码" -> if(operation == AuthOperation.REGISTER || operation == AuthOperation.RESET_PASSWORD)
            "验证码错误或已失效，请检查验证码，或重新获取后再试" else null
        "未知的应用", "无效的请求类型" -> "应用版本与认证服务不兼容，请更新应用后重试"
        "邮箱必须是一个有效的邮箱" -> "邮箱格式不正确，请检查邮箱地址"
        "用户名长度必须至少为2个字符", "用户名长度不能超过16个字符" -> "用户名应为 2–16 个字符"
        "用户名前后不能有空格" -> "用户名前后不能有空格，请检查后重试"
        "用户名只能包含可打印字符" -> "用户名包含不可见字符，请重新输入"
        "用户名不能包含@字符" -> "注册用户名不能包含 @，请将邮箱填写在邮箱栏"
        "密码长度必须至少为8个字符", "密码长度不能超过100个字符" -> "密码应为 8–100 个字符"
        "密码只能包含可打印字符" -> "密码包含不可见字符，请重新输入"
        "密码不能包含空格" -> "密码不能包含空格，请检查后重试"
        "验证码必须是一个有效的数值", "验证码长度必须是6个字符" -> if(operation == AuthOperation.REGISTER)
            "注册验证码应为 6 位数字，请检查邮件中的验证码" else null
        "验证码长度必须是26个字符" -> if(operation == AuthOperation.RESET_PASSWORD)
            "找回密码验证码应为 26 位字符，请完整复制邮件中的验证码" else null
        "人机验证失败，请重试" -> if(operation == AuthOperation.REGISTER_OTP || operation == AuthOperation.RESET_OTP)
            "人机验证未通过，未能发送邮箱验证码" else null
        else -> null
    }
    401 -> when {
        operation == AuthOperation.LOGIN && reason == "密码错误" -> "密码错误，请重新输入；忘记密码可使用“找回密码”"
        operation == AuthOperation.RESET_PASSWORD && reason == "无效的验证码" -> "验证码错误或已失效，请重新获取找回密码验证码"
        else -> null
    }
    403 -> when {
        operation == AuthOperation.LOGIN && reason == "用户已被封禁" -> "账号已被封禁，无法登录，请联系站方了解详情"
        operation.registration && reason == "注册功能已关闭" -> "网站暂时关闭了注册，请稍后再试；已有账号可直接登录"
        operation.passwordReset && reason == "重置密码功能已关闭" -> "网站暂时关闭了找回密码，请稍后再试或联系站方"
        else -> null
    }
    404 -> when {
        reason != "用户不存在" -> null
        operation == AuthOperation.LOGIN -> "账号不存在，请检查用户名或注册邮箱是否填写正确"
        operation.passwordReset -> "该邮箱未关联账号，请填写注册时使用的邮箱"
        else -> null
    }
    409 -> when {
        operation == AuthOperation.REGISTER && reason == "用户名已被占用" -> "用户名已被占用，请更换用户名；如为本人账号可直接登录"
        operation.registration && reason in setOf("邮箱已被占用", "邮箱已经被使用") -> "该邮箱已注册，请直接登录；忘记密码可使用“找回密码”"
        else -> null
    }
    else -> null
}

private fun authErrorReason(response: Response): String? {
    if(response.code !in setOf(400, 401, 403, 404, 409, 422)) return null
    if(response.body?.contentType()?.let { it.type == "text" && it.subtype == "plain" } != true) return null
    // 多读一个字节以识别截断，不能把长响应开头恰好匹配的内容当作业务原因。
    return response.peekBody(1025).use { preview ->
        if(preview.contentLength() > 1024) null else preview.string().trim()
    }
}
