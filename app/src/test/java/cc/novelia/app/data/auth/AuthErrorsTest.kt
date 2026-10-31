package cc.novelia.app.data.auth

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class AuthErrorsTest {
    private fun response(operation: AuthOperation, status: Int, body: String, contentType: String?) = Response.Builder()
        .request(Request.Builder().url("https://auth.example.invalid/api/v1/auth/${operation.path}")
            .post("{}".toRequestBody()).build())
        .protocol(Protocol.HTTP_1_1).code(status).message("Synthetic response")
        .body(body.toResponseBody(contentType?.toMediaType())).build()

    private fun failure(operation: AuthOperation, status: Int, body: String = "", contentType: String? = "text/plain; charset=utf-8") =
        response(operation, status, body, contentType).use { authenticationError(operation, it) }

    @Test fun missingLoginAccountIsNotReportedAsAnUnavailableService() {
        val body = "用户不存在\n"
        response(AuthOperation.LOGIN, 404, body, "text/plain; charset=utf-8").use {
            val error = authenticationError(AuthOperation.LOGIN, it)
            assertEquals(404, error.status)
            assertEquals("账号不存在，请检查用户名或注册邮箱是否填写正确", error.message)
            assertEquals(body, it.body!!.string())
        }
    }

    @Test fun gatewayAndUnrecognized404DoNotClaimTheAccountIsMissing() {
        for((body, contentType) in listOf(
            "404 page not found" to "text/plain",
            "<html>用户不存在</html>" to "text/html",
            "用户不存在" to "application/json",
            "用户不存在" to null,
            "" to "text/plain",
        )) {
            val error = failure(AuthOperation.LOGIN, 404, body, contentType)
            assertEquals(404, error.status)
            assertTrue(error.message.contains("404"))
            assertTrue(error.message.contains("切换书源"))
            assertFalse(error.message.contains("账号不存在"))
            assertFalse(error.message.contains("服务暂不可用"))
        }
    }

    @Test fun resetUsesEmailAndOtpGuidanceInsteadOfLoginPasswordGuidance() {
        for(operation in listOf(AuthOperation.RESET_OTP, AuthOperation.RESET_PASSWORD)) {
            assertEquals("该邮箱未关联账号，请填写注册时使用的邮箱", failure(operation, 404, "用户不存在").message)
        }
        val invalidOtp = failure(AuthOperation.RESET_PASSWORD, 401, "无效的验证码")
        assertEquals(401, invalidOtp.status)
        assertEquals("验证码错误或已失效，请重新获取找回密码验证码", invalidOtp.message)
        assertFalse(failure(AuthOperation.RESET_PASSWORD, 401).message.contains("账号和密码"))
        assertTrue(failure(AuthOperation.LOGIN, 401, "密码错误").message.startsWith("密码错误"))
        assertTrue(failure(AuthOperation.REGISTER, 400, "无效验证码").message.contains("验证码错误或已失效"))
        assertFalse(failure(AuthOperation.REGISTER_OTP, 404, "用户不存在").message.contains("邮箱未关联账号"))
        assertTrue(failure(AuthOperation.RESET_PASSWORD, 400, "验证码长度必须是26个字符").message.contains("26 位字符"))
        assertTrue(failure(AuthOperation.REGISTER, 400, "验证码长度必须是6个字符").message.contains("6 位数字"))
        assertFalse(failure(AuthOperation.RESET_PASSWORD, 400, "验证码长度必须是6个字符").message.contains("6 位数字"))
    }

    @Test fun accountRestrictionsAndClosedFeaturesHaveActionableMessages() {
        assertTrue(failure(AuthOperation.LOGIN, 403, "用户已被封禁").message.contains("账号已被封禁"))
        for(operation in listOf(AuthOperation.REGISTER, AuthOperation.REGISTER_OTP)) {
            assertTrue(failure(operation, 403, "注册功能已关闭").message.contains("关闭了注册"))
        }
        for(operation in listOf(AuthOperation.RESET_PASSWORD, AuthOperation.RESET_OTP)) {
            assertTrue(failure(operation, 403, "重置密码功能已关闭").message.contains("关闭了找回密码"))
        }
        assertFalse(failure(AuthOperation.LOGIN, 403, "注册功能已关闭").message.contains("关闭了注册"))
        assertFalse(failure(AuthOperation.LOGIN, 403, "<html>用户已被封禁</html>", "text/html").message.contains("账号已被封禁"))
    }

    @Test fun registrationDistinguishesTakenUsernameFromRegisteredEmail() {
        val username = failure(AuthOperation.REGISTER, 409, "用户名已被占用")
        assertEquals(409, username.status)
        assertTrue(username.message.contains("更换用户名"))
        assertFalse(username.message.contains("邮箱已注册"))
        assertTrue(failure(AuthOperation.REGISTER, 409, "邮箱已被占用").message.contains("邮箱已注册"))
        assertTrue(failure(AuthOperation.REGISTER_OTP, 409, "邮箱已经被使用").message.contains("邮箱已注册"))
        assertFalse(failure(AuthOperation.RESET_PASSWORD, 409, "邮箱已被占用").message.contains("邮箱已注册"))
    }

    @Test fun unknownOrOversizedBodiesNeverBecomeUserFacingDetails() {
        val fallback = failure(AuthOperation.LOGIN, 404).message
        for(body in listOf(
            "用户不存在：synthetic-account@example.invalid",
            "用户不存在\nsynthetic-private-details",
            "用户不存在" + " ".repeat(1100),
            "用户不存在<script>synthetic</script>",
        )) {
            assertEquals(fallback, failure(AuthOperation.LOGIN, 404, body).message)
        }
        assertEquals(failure(AuthOperation.REGISTER, 400).message,
            failure(AuthOperation.REGISTER, 400, "密码不能包含空格：synthetic-private-details").message)
        assertTrue(failure(AuthOperation.REGISTER, 400, "密码不能包含空格").message.startsWith("密码不能包含空格"))
        assertEquals("人机验证未通过，未能发送邮箱验证码",
            failure(AuthOperation.RESET_OTP, 400, "人机验证失败，请重试").message)
    }

    @Test fun serviceFailuresKeepStatusAndDoNotBorrowAccountErrorReasons() {
        for(status in listOf(302, 405, 408, 429, 500, 502, 503, 504)) {
            val generic = failure(AuthOperation.LOGIN, status)
            assertEquals(status, generic.status)
            assertEquals(generic.message, failure(AuthOperation.LOGIN, status, "用户不存在").message)
            assertEquals(generic.message, failure(AuthOperation.LOGIN, status, "synthetic-private-details").message)
        }
        assertTrue(failure(AuthOperation.LOGIN, 504).message.contains("超时"))
        assertTrue(failure(AuthOperation.LOGIN, 429).message.contains("过于频繁"))
        assertFalse(failure(AuthOperation.LOGIN, 502, "密码错误").message.startsWith("密码错误"))
    }
}
