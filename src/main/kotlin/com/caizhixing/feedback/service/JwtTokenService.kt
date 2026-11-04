package com.caizhixing.feedback.service

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTDecodeException
import com.auth0.jwt.exceptions.JWTVerificationException
import java.time.Clock
import java.time.Instant

data class IssuedToken(val token: String, val expiresIn: Long)

sealed interface TokenVerification {
    data class Valid(val username: String) : TokenVerification
    data object Expired : TokenVerification
    data object Invalid : TokenVerification
}

class JwtTokenService(secret: String, private val clock: Clock) {

    private val algorithm: Algorithm = Algorithm.HMAC256(secret)

    fun issue(username: String): IssuedToken {
        val issuedAt = clock.instant()
        val token = JWT.create()
            .withSubject(username)
            .withIssuedAt(issuedAt)
            .withExpiresAt(issuedAt.plusSeconds(EXPIRES_IN_SECONDS))
            .sign(algorithm)
        return IssuedToken(token = token, expiresIn = EXPIRES_IN_SECONDS)
    }

    // 过期比对用注入时钟而非库内系统时钟（java-jwt 的 verifier 内部取 new Date()，不可注入），
    // 使 40101 判定可确定性地测试；签名校验仍完全交给 java-jwt，不自研密码学
    fun verify(token: String): TokenVerification {
        val decoded = try {
            JWT.decode(token)
        } catch (_: JWTDecodeException) {
            return TokenVerification.Invalid
        }
        return try {
            algorithm.verify(decoded)
            val expiresAt: Instant? = decoded.expiresAtAsInstant
            when {
                expiresAt != null && !clock.instant().isBefore(expiresAt) -> TokenVerification.Expired
                else -> decoded.subject?.takeIf { it.isNotBlank() }
                    ?.let { TokenVerification.Valid(it) }
                    ?: TokenVerification.Invalid
            }
        } catch (_: JWTVerificationException) {
            TokenVerification.Invalid
        }
    }

    companion object {
        const val EXPIRES_IN_SECONDS: Long = 604800L
    }
}
