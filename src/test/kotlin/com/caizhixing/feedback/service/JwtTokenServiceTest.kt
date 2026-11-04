package com.caizhixing.feedback.service

import com.auth0.jwt.JWT
import com.caizhixing.feedback.service.TokenVerification.Expired
import com.caizhixing.feedback.service.TokenVerification.Invalid
import com.caizhixing.feedback.service.TokenVerification.Valid
import com.caizhixing.feedback.util.MutableClock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class JwtTokenServiceTest {

    private val secret = "0123456789abcdef0123456789abcdef"
    private val clock = MutableClock(Instant.parse("2026-11-04T04:00:00Z"))

    @Test
    fun issuesHs256TokenValidForSevenDays() {
        val service = JwtTokenService(secret, clock)

        val issued = service.issue("admin")

        assertEquals(604800L, issued.expiresIn)
        assertEquals("HS256", JWT.decode(issued.token).algorithm)
        assertEquals(Valid("admin"), service.verify(issued.token))
    }

    @Test
    fun tokenValidOneSecondBeforeExpiryBoundary() {
        val service = JwtTokenService(secret, clock)
        val issued = service.issue("admin")

        clock.advanceSeconds(JwtTokenService.EXPIRES_IN_SECONDS - 1)

        assertEquals(Valid("admin"), service.verify(issued.token))
    }

    @Test
    fun tokenExpiredAtSevenDaysBoundaryAndBeyond() {
        val service = JwtTokenService(secret, clock)
        val issued = service.issue("admin")

        clock.advanceSeconds(JwtTokenService.EXPIRES_IN_SECONDS)
        assertEquals(Expired, service.verify(issued.token))

        clock.advanceSeconds(1)
        assertEquals(Expired, service.verify(issued.token))
    }

    @Test
    fun garbageTokenIsInvalid() {
        val service = JwtTokenService(secret, clock)

        assertEquals(Invalid, service.verify("not-a-jwt"))
        assertEquals(Invalid, service.verify("aaa.bbb.ccc"))
        assertEquals(Invalid, service.verify(""))
    }

    @Test
    fun tokenSignedWithDifferentSecretIsInvalid() {
        val forger = JwtTokenService("forged-secret-forged-secret-forged-secret!", MutableClock(Instant.parse("2026-11-04T04:00:00Z")))
        val forged = forger.issue("admin")

        assertEquals(Invalid, JwtTokenService(secret, clock).verify(forged.token))
    }

    @Test
    fun tokenWithoutSubjectIsInvalid() {
        val service = JwtTokenService(secret, clock)

        val issued = service.issue("")

        assertEquals(Invalid, service.verify(issued.token))
    }
}
