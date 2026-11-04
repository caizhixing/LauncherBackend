package com.caizhixing.feedback.service

import at.favre.lib.crypto.bcrypt.BCrypt
import com.caizhixing.feedback.model.ApiException
import com.caizhixing.feedback.model.ErrorCode
import com.caizhixing.feedback.model.dto.LoginRequest
import com.caizhixing.feedback.service.TokenVerification.Valid
import com.caizhixing.feedback.util.FixedWindowRateLimiter
import com.caizhixing.feedback.util.MutableClock
import com.caizhixing.feedback.util.RateLimiter
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AuthServiceTest {

    companion object {
        private const val USERNAME = "admin"
        private const val PASSWORD = "correct-horse-battery"
        private const val SECRET = "0123456789abcdef0123456789abcdef"
        private val PASSWORD_HASH: String by lazy { BCrypt.withDefaults().hashToString(10, PASSWORD.toCharArray()) }
    }

    private fun service(clock: MutableClock, limiter: RateLimiter): AuthService =
        AuthService(USERNAME, PASSWORD_HASH, JwtTokenService(SECRET, clock), limiter)

    private fun unlimitedLimiter(clock: MutableClock): RateLimiter =
        FixedWindowRateLimiter(minuteLimit = 1000, dayLimit = null, clock = clock)

    @Test
    fun loginSuccess() {
        val clock = MutableClock(Instant.parse("2026-11-04T04:00:00Z"))
        val tokenService = JwtTokenService(SECRET, clock)
        val service = AuthService(USERNAME, PASSWORD_HASH, tokenService, unlimitedLimiter(clock))

        val response = runBlocking { service.login(LoginRequest(USERNAME, PASSWORD), "203.0.113.7") }

        assertEquals(604800L, response.expiresIn)
        assertTrue(response.token.isNotBlank())
        assertEquals(Valid(USERNAME), tokenService.verify(response.token))
    }

    @Test
    fun badCredentialsUnified() {
        val clock = MutableClock(Instant.parse("2026-11-04T04:00:00Z"))
        val service = service(clock, unlimitedLimiter(clock))

        val wrongUsername = assertFailsWith<ApiException> {
            runBlocking { service.login(LoginRequest("root", PASSWORD), "203.0.113.7") }
        }
        val wrongPassword = assertFailsWith<ApiException> {
            runBlocking { service.login(LoginRequest(USERNAME, "wrong-password"), "203.0.113.7") }
        }

        assertEquals(ErrorCode.BAD_CREDENTIALS, wrongUsername.code)
        assertEquals(ErrorCode.BAD_CREDENTIALS, wrongPassword.code)
        assertEquals(wrongUsername.message, wrongPassword.message)
    }

    @Test
    fun loginRateLimited() {
        val clock = MutableClock(Instant.parse("2026-11-04T04:00:30Z"))
        val limiter = FixedWindowRateLimiter(minuteLimit = 5, dayLimit = null, clock = clock)
        val service = service(clock, limiter)

        repeat(5) {
            val failure = assertFailsWith<ApiException> {
                runBlocking { service.login(LoginRequest("root", "probe"), "198.51.100.9") }
            }
            assertEquals(ErrorCode.BAD_CREDENTIALS, failure.code)
        }
        val denied = assertFailsWith<ApiException> {
            runBlocking { service.login(LoginRequest(USERNAME, PASSWORD), "198.51.100.9") }
        }
        assertEquals(ErrorCode.RATE_LIMITED, denied.code)

        clock.advanceSeconds(30) // 04:01:30Z：跨入下一分钟窗口
        val recovered = runBlocking { service.login(LoginRequest(USERNAME, PASSWORD), "198.51.100.9") }
        assertEquals(604800L, recovered.expiresIn)
    }

    @Test
    fun rateLimitKeyedByClientIp() {
        val clock = MutableClock(Instant.parse("2026-11-04T04:00:00Z"))
        val limiter = FixedWindowRateLimiter(minuteLimit = 5, dayLimit = null, clock = clock)
        val service = service(clock, limiter)

        repeat(5) {
            assertFailsWith<ApiException> {
                runBlocking { service.login(LoginRequest("root", "probe"), "198.51.100.9") }
            }
        }
        val denied = assertFailsWith<ApiException> {
            runBlocking { service.login(LoginRequest("root", "probe"), "198.51.100.9") }
        }
        assertEquals(ErrorCode.RATE_LIMITED, denied.code)

        val otherIp = runBlocking { service.login(LoginRequest(USERNAME, PASSWORD), "192.0.2.5") }
        assertEquals(604800L, otherIp.expiresIn)
    }
}
