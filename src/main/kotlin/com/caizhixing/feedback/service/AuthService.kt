package com.caizhixing.feedback.service

import at.favre.lib.crypto.bcrypt.BCrypt
import com.caizhixing.feedback.model.ApiException
import com.caizhixing.feedback.model.ErrorCode
import com.caizhixing.feedback.model.dto.LoginRequest
import com.caizhixing.feedback.model.dto.LoginResponse
import com.caizhixing.feedback.util.RateLimitResult
import com.caizhixing.feedback.util.RateLimiter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AuthService(
    private val adminUsername: String,
    private val adminPasswordHash: String,
    private val tokenService: JwtTokenService,
    private val loginRateLimiter: RateLimiter,
) {

    suspend fun login(request: LoginRequest, clientIp: String): LoginResponse {
        validate(request)
        when (loginRateLimiter.tryAcquire(clientIp)) {
            is RateLimitResult.Denied -> throw ApiException(ErrorCode.RATE_LIMITED, "登录过于频繁，请稍后再试")
            RateLimitResult.Allowed -> Unit
        }
        val credentialsOk = withContext(Dispatchers.IO) { matchesAdmin(request) }
        if (!credentialsOk) throw ApiException(ErrorCode.BAD_CREDENTIALS, "用户名或密码错误")
        val issued = tokenService.issue(adminUsername)
        return LoginResponse(token = issued.token, expiresIn = issued.expiresIn)
    }

    private fun validate(request: LoginRequest) {
        if (request.username.isBlank()) throw ApiException(ErrorCode.BAD_REQUEST, "username 不能为空")
        if (request.password.isBlank()) throw ApiException(ErrorCode.BAD_REQUEST, "password 不能为空")
    }

    // 密码哈希校验恒执行（用户名不符时校验占位哈希），消除按响应耗时探测用户名是否正确的侧信道
    private val usernameMismatchHash: String by lazy {
        BCrypt.withDefaults().hashToString(DUMMY_BCRYPT_COST, DUMMY_PASSWORD.toCharArray())
    }

    private fun matchesAdmin(request: LoginRequest): Boolean {
        val usernameMatches = request.username == adminUsername
        val hash = if (usernameMatches) adminPasswordHash else usernameMismatchHash
        val passwordMatches = BCrypt.verifyer().verify(request.password.toCharArray(), hash).verified
        return usernameMatches && passwordMatches
    }

    companion object {
        const val LOGIN_RATE_LIMIT_PER_MINUTE: Int = 5

        private const val DUMMY_BCRYPT_COST: Int = 10
        private const val DUMMY_PASSWORD: String = "not-the-admin-password"
    }
}
