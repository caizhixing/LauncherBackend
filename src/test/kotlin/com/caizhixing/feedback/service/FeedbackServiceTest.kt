package com.caizhixing.feedback.service

import com.caizhixing.feedback.model.ApiException
import com.caizhixing.feedback.model.ErrorCode
import com.caizhixing.feedback.model.Feedback
import com.caizhixing.feedback.model.FeedbackCategory
import com.caizhixing.feedback.model.FeedbackStatus
import com.caizhixing.feedback.model.dto.SubmitFeedbackRequest
import com.caizhixing.feedback.model.dto.SubmitFeedbackResponse
import com.caizhixing.feedback.repository.FeedbackRepository
import com.caizhixing.feedback.util.AppTime
import com.caizhixing.feedback.util.FixedWindowRateLimiter
import com.caizhixing.feedback.util.RateLimitResult
import com.caizhixing.feedback.util.RateLimiter
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 行为 4.1/4.3/4.4/4.5：fake Repository + 注入 Clock；
 * 限流路径直接复用 #2 的 FixedWindowRateLimiter（1/分 + 5/天，同实例两窗口）。
 */
class FeedbackServiceTest {

    private class MutableClock(start: Instant) : Clock() {
        private var current = start

        override fun getZone(): ZoneId = ZoneId.of("UTC")

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current

        fun advanceSeconds(seconds: Long) {
            current = current.plusSeconds(seconds)
        }
    }

    private class FakeFeedbackRepository : FeedbackRepository {
        val inserted = mutableListOf<Pair<Long, Feedback>>()
        private var nextId = 42L

        override suspend fun insert(feedback: Feedback): Long {
            val id = nextId++
            inserted += id to feedback
            return id
        }

        override suspend fun findById(id: Long): Feedback? = inserted.firstOrNull { it.first == id }?.second
    }

    private class AllowAllRateLimiter : RateLimiter {
        val keys = mutableListOf<String>()

        override fun tryAcquire(key: String): RateLimitResult {
            keys += key
            return RateLimitResult.Allowed
        }
    }

    private fun validRequest(deviceId: String = "device-1"): SubmitFeedbackRequest = SubmitFeedbackRequest(
        title = "启动崩溃",
        content = "点击图标后闪退",
        category = "BUG",
        appName = "launcher",
        appVersion = "1.2.3",
        deviceId = deviceId,
        deviceModel = "Pixel 9",
        osVersion = "15",
        contact = "a@b.c",
    )

    @Test
    fun submitSuccessFillsServerSideFields() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-11-04T04:00:00Z")) // 上海 12:00:00
        val repository = FakeFeedbackRepository()
        val limiter = AllowAllRateLimiter()
        val service = FeedbackService(repository, limiter, clock)

        val response = service.submit(validRequest())

        assertEquals(SubmitFeedbackResponse(feedbackId = 42L), response)
        val saved = repository.inserted.single().second
        assertEquals(null, saved.id)
        assertEquals("启动崩溃", saved.title)
        assertEquals("点击图标后闪退", saved.content)
        assertEquals(FeedbackCategory.BUG, saved.category)
        assertEquals("launcher", saved.appName)
        assertEquals("1.2.3", saved.appVersion)
        assertEquals("Pixel 9", saved.deviceModel)
        assertEquals("15", saved.osVersion)
        assertEquals("device-1", saved.deviceId)
        assertEquals("a@b.c", saved.contact)
        assertEquals(FeedbackStatus.PENDING, saved.status)
        assertEquals(emptyList<String>(), saved.images)
        val expectedNow = LocalDateTime.ofInstant(clock.instant(), AppTime.ZONE)
        assertEquals(expectedNow, saved.createdAt)
        assertEquals(saved.createdAt, saved.updatedAt) // createdAt=updatedAt 同一时钟值
        assertEquals(listOf("device-1"), limiter.keys) // 限流 key 为 deviceId
    }

    @Test
    fun submitRateLimitedPerMinute() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-11-04T04:00:00Z"))
        val repository = FakeFeedbackRepository()
        val service = FeedbackService(repository, FixedWindowRateLimiter(minuteLimit = 1, dayLimit = 5, clock), clock)

        assertEquals(SubmitFeedbackResponse(feedbackId = 42L), service.submit(validRequest()))

        val error = assertFailsWith<ApiException> { service.submit(validRequest()) } // 同分钟第 2 条
        assertEquals(ErrorCode.RATE_LIMITED, error.code)
        assertEquals("提交过于频繁，请稍后再试", error.message)
        assertEquals(1, repository.inserted.size) // 被拒请求未落库
    }

    @Test
    fun submitRateLimitedPerDay() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-11-04T04:00:00Z"))
        val repository = FakeFeedbackRepository()
        val service = FeedbackService(repository, FixedWindowRateLimiter(minuteLimit = 1, dayLimit = 5, clock), clock)

        repeat(5) {
            assertEquals(
                SubmitFeedbackResponse(feedbackId = 42L + it),
                service.submit(validRequest()),
            )
            clock.advanceSeconds(60) // 逐分钟提交，避开 1/分限制，只消耗当天额度
        }

        clock.advanceSeconds(60) // 第 6 条进入新分钟窗口，但当日额度已耗尽
        val error = assertFailsWith<ApiException> { service.submit(validRequest()) }
        assertEquals(ErrorCode.RATE_LIMITED, error.code)
        assertEquals(5, repository.inserted.size)
    }

    @Test
    fun rateLimitKeyedByDeviceId() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-11-04T04:00:00Z"))
        val repository = FakeFeedbackRepository()
        val service = FeedbackService(repository, FixedWindowRateLimiter(minuteLimit = 1, dayLimit = 5, clock), clock)

        service.submit(validRequest(deviceId = "device-a"))
        service.submit(validRequest(deviceId = "device-b")) // 同分钟不同设备互不影响

        assertEquals(2, repository.inserted.size)
        assertEquals(setOf("device-a", "device-b"), repository.inserted.map { it.second.deviceId }.toSet())

        val error = assertFailsWith<ApiException> { service.submit(validRequest(deviceId = "device-a")) } // a 的第 2 条
        assertEquals(ErrorCode.RATE_LIMITED, error.code)
        assertEquals(2, repository.inserted.size) // b 不受 a 限流影响
    }

    @Test
    fun invalidRequestRejectedBeforeRateLimitAndInsert() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-11-04T04:00:00Z"))
        val repository = FakeFeedbackRepository()
        val limiter = AllowAllRateLimiter()
        val service = FeedbackService(repository, limiter, clock)

        val error = assertFailsWith<ApiException> {
            service.submit(validRequest().copy(title = ""))
        }

        assertEquals(ErrorCode.BAD_REQUEST, error.code)
        assertEquals("title: 必填", error.message)
        assertEquals(0, repository.inserted.size)
        assertEquals(0, limiter.keys.size) // 校验先于限流：非法请求不消耗限流配额
    }

    @Test
    fun missingRequiredFieldRejectedWithFieldMessage() = runBlocking {
        // round1 blocker1：请求体缺必填字段（DTO 可空建模为 null）逐字段出 40001 message
        val clock = MutableClock(Instant.parse("2026-11-04T04:00:00Z"))
        val repository = FakeFeedbackRepository()
        val limiter = AllowAllRateLimiter()
        val service = FeedbackService(repository, limiter, clock)

        val error = assertFailsWith<ApiException> {
            service.submit(validRequest().copy(title = null, contact = null))
        }

        assertEquals(ErrorCode.BAD_REQUEST, error.code)
        assertEquals("title: 必填", error.message)
        assertEquals(0, repository.inserted.size)
        assertEquals(0, limiter.keys.size)
    }
}
