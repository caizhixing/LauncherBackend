package com.caizhixing.feedback.util

import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class AppTimeTest {

    @Test
    fun zoneIsAsiaShanghai() {
        assertEquals(ZoneId.of("Asia/Shanghai"), AppTime.ZONE)
    }

    @Test
    fun nowConvertsInstantToShanghaiWallClock() {
        // UTC 16:05:06 + 8h = 次日 00:05:06，跨日验证时区换算而非时钟原值透传
        val utcClock = Clock.fixed(Instant.parse("2026-11-04T16:05:06Z"), ZoneOffset.UTC)
        assertEquals(LocalDateTime.of(2026, 11, 5, 0, 5, 6), AppTime.now(utcClock))
    }

    @Test
    fun formatProducesContractPattern() {
        assertEquals("2026-11-04 12:30:15", AppTime.format(LocalDateTime.of(2026, 11, 4, 12, 30, 15)))
    }

    @Test
    fun formatPadsSingleDigitParts() {
        assertEquals("2026-01-02 03:04:05", AppTime.format(LocalDateTime.of(2026, 1, 2, 3, 4, 5)))
    }
}
