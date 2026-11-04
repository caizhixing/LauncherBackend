package com.caizhixing.feedback.service

import com.caizhixing.feedback.model.ApiException
import com.caizhixing.feedback.model.ErrorCode
import com.caizhixing.feedback.model.dto.LoginRequest
import com.caizhixing.feedback.util.FixedWindowRateLimiter
import com.caizhixing.feedback.util.MutableClock
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 行为 3.3：username/password 缺失（归一为空串）或空白 → 40001，message 指明字段。
 * 校验先于限流与凭据比对执行，故占位哈希不会被触达。
 */
class LoginRequestTest {

    private val clock = MutableClock(Instant.parse("2026-11-04T04:00:00Z"))

    private val service = AuthService(
        adminUsername = "admin",
        adminPasswordHash = "never-reached-placeholder",
        tokenService = JwtTokenService("0123456789abcdef0123456789abcdef", clock),
        loginRateLimiter = FixedWindowRateLimiter(minuteLimit = 5, dayLimit = null, clock = clock),
    )

    @Test
    fun blankUsernameRejected() {
        val failure = assertFailsWith<ApiException> {
            runBlocking { service.login(LoginRequest(username = "", password = "x"), "203.0.113.7") }
        }
        assertEquals(ErrorCode.BAD_REQUEST, failure.code)
        assertTrue(failure.message.contains("username"))
    }

    @Test
    fun whitespaceUsernameRejected() {
        val failure = assertFailsWith<ApiException> {
            runBlocking { service.login(LoginRequest(username = "   ", password = "x"), "203.0.113.7") }
        }
        assertEquals(ErrorCode.BAD_REQUEST, failure.code)
        assertTrue(failure.message.contains("username"))
    }

    @Test
    fun blankPasswordRejected() {
        val failure = assertFailsWith<ApiException> {
            runBlocking { service.login(LoginRequest(username = "admin", password = ""), "203.0.113.7") }
        }
        assertEquals(ErrorCode.BAD_REQUEST, failure.code)
        assertTrue(failure.message.contains("password"))
    }

    @Test
    fun missingFieldsDefaultToBlankAndRejected() {
        val failure = assertFailsWith<ApiException> {
            runBlocking { service.login(LoginRequest(), "203.0.113.7") }
        }
        assertEquals(ErrorCode.BAD_REQUEST, failure.code)
        assertTrue(failure.message.contains("username"))
    }

    @Test
    fun missingFieldSemanticsEqualBlankString() {
        assertEquals(LoginRequest(username = "", password = "x"), LoginRequest(password = "x"))
        assertEquals(LoginRequest(username = "x", password = ""), LoginRequest(username = "x"))
    }
}
