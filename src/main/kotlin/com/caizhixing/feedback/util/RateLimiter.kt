package com.caizhixing.feedback.util

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

sealed interface RateLimitResult {
    data object Allowed : RateLimitResult

    data class Denied(val retryAfterSeconds: Long) : RateLimitResult
}

interface RateLimiter {
    fun tryAcquire(key: String): RateLimitResult
}

private const val SECONDS_PER_MINUTE = 60L

class FixedWindowRateLimiter(
    private val minuteLimit: Int,
    private val dayLimit: Int?,
    private val clock: Clock,
) : RateLimiter {

    init {
        require(minuteLimit >= 1) { "minuteLimit must be >= 1" }
        require(dayLimit == null || dayLimit >= 1) { "dayLimit must be >= 1" }
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    // 天窗口锚定 Asia/Shanghai 自然日（PRD 决策 5），与 clock 自带时区无关
    private val dayWindowZone: ZoneId = ZoneId.of("Asia/Shanghai")

    internal val entryCount: Int
        get() = entries.size

    override fun tryAcquire(key: String): RateLimitResult {
        val now = clock.instant()
        val currentMinuteId = now.epochSecond / SECONDS_PER_MINUTE
        val currentDayId = now.atZone(dayWindowZone).toLocalDate().toEpochDay()
        evictExpired(currentMinuteId, currentDayId)
        var outcome: RateLimitResult = RateLimitResult.Allowed
        entries.compute(key) { _, entry ->
            val current = entry?.refreshedTo(currentMinuteId, currentDayId)
                ?: Entry(WindowState(currentMinuteId, 0), WindowState(currentDayId, 0))
            if (current.hasQuotaIn(minuteLimit, dayLimit)) {
                current.incremented()
            } else {
                // 被拒的请求不消耗配额，持续重试不会把锁定窗口无限延长
                outcome = RateLimitResult.Denied(
                    secondsUntilQuotaReturns(now, currentMinuteId, currentDayId, current),
                )
                current
            }
        }
        return outcome
    }

    private fun evictExpired(currentMinuteId: Long, currentDayId: Long) {
        // 天窗口仍处当日的条目必须保留（跨分钟后当日额度依然生效），两个窗口都成为过去才可清理
        entries.entries.removeIf { (_, entry) ->
            entry.minute.windowId < currentMinuteId &&
                (dayLimit == null || entry.day.windowId < currentDayId)
        }
    }

    private fun secondsUntilQuotaReturns(
        now: Instant,
        currentMinuteId: Long,
        currentDayId: Long,
        current: Entry,
    ): Long {
        val minuteExhausted = current.minute.count >= minuteLimit
        val dayExhausted = dayLimit != null && current.day.count >= dayLimit
        val untilMinuteEnd = (currentMinuteId + 1) * SECONDS_PER_MINUTE - now.epochSecond
        val untilDayEnd = Duration.between(
            now,
            LocalDate.ofEpochDay(currentDayId + 1).atStartOfDay(dayWindowZone).toInstant(),
        ).seconds
        // 两窗口同时耗尽时需等更晚者；上海 00:00 的天边界同时是分钟边界，取 max 即恢复点
        return when {
            minuteExhausted && dayExhausted -> maxOf(untilMinuteEnd, untilDayEnd)
            minuteExhausted -> untilMinuteEnd
            else -> untilDayEnd
        }
    }

    private data class WindowState(val windowId: Long, val count: Int)

    private data class Entry(val minute: WindowState, val day: WindowState) {

        fun refreshedTo(currentMinuteId: Long, currentDayId: Long): Entry = copy(
            minute = if (minute.windowId == currentMinuteId) minute else WindowState(currentMinuteId, 0),
            day = if (day.windowId == currentDayId) day else WindowState(currentDayId, 0),
        )

        fun hasQuotaIn(minuteLimit: Int, dayLimit: Int?): Boolean =
            minute.count < minuteLimit && (dayLimit == null || day.count < dayLimit)

        fun incremented(): Entry = copy(
            minute = minute.copy(count = minute.count + 1),
            day = day.copy(count = day.count + 1),
        )
    }
}
