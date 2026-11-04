package com.caizhixing.feedback.util

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class MutableClock(start: Instant) : Clock() {
    private var current = start

    override fun getZone(): ZoneId = ZoneId.of("UTC")

    override fun withZone(zone: ZoneId): Clock = MutableClock(current)

    override fun instant(): Instant = current

    fun advanceSeconds(seconds: Long) {
        current = current.plusSeconds(seconds)
    }
}
