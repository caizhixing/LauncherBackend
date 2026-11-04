package com.caizhixing.feedback.util

import com.caizhixing.feedback.util.RateLimitResult.Allowed
import com.caizhixing.feedback.util.RateLimitResult.Denied
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RateLimiterTest {

    @Test
    fun overLimitDenied() {
        val clock = MutableClock(Instant.parse("2026-11-04T12:00:30Z"))
        val limiter = FixedWindowRateLimiter(minuteLimit = 3, dayLimit = 1000, clock)

        repeat(3) { assertEquals(Allowed, limiter.tryAcquire("device-1")) }
        val denied = assertIs<Denied>(limiter.tryAcquire("device-1"))
        assertEquals(30L, denied.retryAfterSeconds)
    }

    @Test
    fun minuteWindowResets() {
        val clock = MutableClock(Instant.parse("2026-11-04T12:00:30Z"))
        val limiter = FixedWindowRateLimiter(minuteLimit = 2, dayLimit = 1000, clock)

        assertEquals(Allowed, limiter.tryAcquire("ip-1"))
        assertEquals(Allowed, limiter.tryAcquire("ip-1"))
        assertIs<Denied>(limiter.tryAcquire("ip-1"))

        clock.advanceSeconds(30) // 12:01:00Z：epoch 分钟边界；滚动 60s 窗口要到 12:01:30 才会重置
        assertEquals(Allowed, limiter.tryAcquire("ip-1")) // 跨边界第一秒即放行
        assertEquals(Allowed, limiter.tryAcquire("ip-1"))
        assertIs<Denied>(limiter.tryAcquire("ip-1")) // 新窗口配额同样只有 2 次
    }

    @Test
    fun dayWindowResetsAtMidnightShanghai() {
        val clock = MutableClock(Instant.parse("2026-11-04T15:59:59Z")) // 上海 2026-11-04 23:59:59
        val limiter = FixedWindowRateLimiter(minuteLimit = 10, dayLimit = 1, clock)

        assertEquals(Allowed, limiter.tryAcquire("device-1"))
        val denied = assertIs<Denied>(limiter.tryAcquire("device-1")) // 当日额度耗尽，分钟额度未耗尽
        assertEquals(1L, denied.retryAfterSeconds) // 距上海次日 00:00:00 恰 1 秒

        clock.advanceSeconds(1) // 上海 2026-11-05 00:00:00：自然日翻转（滚动 24h 不会在此重置）
        assertEquals(Allowed, limiter.tryAcquire("device-1"))

        // 对照：跨 UTC 午夜（epoch 日边界）不重置，上海仍属同一自然日
        val utcClock = MutableClock(Instant.parse("2026-11-04T23:59:59Z")) // 上海 2026-11-05 07:59:59
        val utcLimiter = FixedWindowRateLimiter(minuteLimit = 10, dayLimit = 1, utcClock)
        assertEquals(Allowed, utcLimiter.tryAcquire("device-1"))
        utcClock.advanceSeconds(1) // UTC 2026-11-05 00:00:00Z = 上海 2026-11-05 08:00:00
        assertIs<Denied>(utcLimiter.tryAcquire("device-1"))
    }

    @Test
    fun keysAreIsolated() {
        val clock = MutableClock(Instant.parse("2026-11-04T12:00:00Z"))
        val limiter = FixedWindowRateLimiter(minuteLimit = 1, dayLimit = 2, clock)

        assertEquals(Allowed, limiter.tryAcquire("device-a"))
        assertIs<Denied>(limiter.tryAcquire("device-a")) // a 的分钟额度耗尽
        assertEquals(Allowed, limiter.tryAcquire("device-b")) // b 不受 a 影响
    }

    @Test
    fun expiredEntriesEvicted() {
        val clock = MutableClock(Instant.parse("2026-11-04T04:00:00Z")) // 上海 12:00:00
        val limiter = FixedWindowRateLimiter(minuteLimit = 1, dayLimit = 5, clock)

        limiter.tryAcquire("device-a")
        limiter.tryAcquire("device-b")
        assertEquals(2, limiter.entryCount)

        clock.advanceSeconds(120) // 上海 12:02：分钟窗口已翻，天窗口仍是当日
        assertEquals(Allowed, limiter.tryAcquire("device-c"))
        assertEquals(3, limiter.entryCount) // 当日额度仍生效，a/b 保留

        clock.advanceSeconds(16 * 3600) // 上海次日 04:02：天窗口也翻过
        assertEquals(Allowed, limiter.tryAcquire("device-d"))
        assertEquals(1, limiter.entryCount) // a/b/c 全部过期，懒清理后条目数有界
    }

    @Test
    fun deniedRetryAfterWaitsForBothWindowsWhenBothExhausted() {
        val clock = MutableClock(Instant.parse("2026-11-04T04:00:30Z")) // 上海 12:00:30
        val limiter = FixedWindowRateLimiter(minuteLimit = 1, dayLimit = 1, clock)

        assertEquals(Allowed, limiter.tryAcquire("device-1"))
        val denied = assertIs<Denied>(limiter.tryAcquire("device-1")) // 分钟与天同时耗尽
        assertEquals(43170L, denied.retryAfterSeconds) // 更晚恢复点 = 上海次日 00:00:00（11h59m30s），而非 30s 后的分钟边界
    }

    @Test
    fun minuteOnlyLimiterEvictsEntriesWithoutDayRetention() {
        val clock = MutableClock(Instant.parse("2026-11-04T04:00:00Z"))
        val limiter = FixedWindowRateLimiter(minuteLimit = 5, dayLimit = null, clock)

        limiter.tryAcquire("ip-1")
        limiter.tryAcquire("ip-2")
        assertEquals(2, limiter.entryCount)

        clock.advanceSeconds(120) // 分钟窗口翻过：无天窗口驻留
        assertEquals(Allowed, limiter.tryAcquire("ip-1"))
        assertEquals(1, limiter.entryCount) // ip-2 的过期条目在下次 acquire 时被懒清理
    }

    @Test
    fun constructorRejectsNonPositiveLimits() {
        val clock = MutableClock(Instant.EPOCH)
        assertFailsWith<IllegalArgumentException> { FixedWindowRateLimiter(minuteLimit = 0, dayLimit = null, clock = clock) }
        assertFailsWith<IllegalArgumentException> { FixedWindowRateLimiter(minuteLimit = 1, dayLimit = 0, clock = clock) }
    }

    private class MutableClock(start: Instant) : Clock() {
        private var current = start

        override fun getZone(): ZoneId = ZoneId.of("UTC")

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current

        fun advanceSeconds(seconds: Long) {
            current = current.plusSeconds(seconds)
        }
    }
}
