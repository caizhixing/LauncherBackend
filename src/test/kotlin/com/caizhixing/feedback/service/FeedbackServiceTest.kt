package com.caizhixing.feedback.service

import com.caizhixing.feedback.model.ApiException
import com.caizhixing.feedback.model.ErrorCode
import com.caizhixing.feedback.model.Feedback
import com.caizhixing.feedback.model.FeedbackCategory
import com.caizhixing.feedback.model.FeedbackListFilter
import com.caizhixing.feedback.model.FeedbackStatus
import com.caizhixing.feedback.model.dto.FeedbackListItem
import com.caizhixing.feedback.model.dto.SubmitFeedbackRequest
import com.caizhixing.feedback.model.dto.SubmitFeedbackResponse
import com.caizhixing.feedback.model.dto.UpdateStatusRequest
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
import kotlin.test.assertTrue

/**
 * 行为 4.1/4.3/4.4/4.5 + 行为 5.7（issue #5 追加 list）：fake Repository + 注入 Clock；
 * 限流路径直接复用 #2 的 FixedWindowRateLimiter（1/分 + 5/天，同实例两窗口）。
 * 行为 6.1–6.5：updateStatus/delete 的值域校验、updatedAt 刷新与 40401 判定。
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
        val statusUpdates = mutableListOf<Triple<Long, FeedbackStatus, LocalDateTime>>()
        val deletedIds = mutableListOf<Long>()
        private var nextId = 42L

        val listQueries = mutableListOf<Triple<FeedbackListFilter, Int, Int>>()
        var listResult: Pair<Long, List<Feedback>> = 0L to emptyList()

        override suspend fun insert(feedback: Feedback): Long {
            val id = nextId++
            inserted += id to feedback
            return id
        }

        override suspend fun findById(id: Long): Feedback? = inserted.firstOrNull { it.first == id }?.second

        override suspend fun findTotalAndList(
            filter: FeedbackListFilter,
            page: Int,
            pageSize: Int,
        ): Pair<Long, List<Feedback>> {
            listQueries += Triple(filter, page, pageSize)
            return listResult
        }

        override suspend fun updateStatus(id: Long, status: FeedbackStatus, updatedAt: LocalDateTime): Boolean {
            statusUpdates += Triple(id, status, updatedAt)
            val index = inserted.indexOfFirst { it.first == id }
            if (index < 0) return false
            val (existingId, existing) = inserted[index]
            inserted[index] = existingId to existing.copy(status = status, updatedAt = updatedAt)
            return true
        }

        override suspend fun delete(id: Long): Boolean {
            deletedIds += id
            return inserted.removeAll { it.first == id }
        }
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

    // 行为 5.7：service 全字段委托 filter（fake repo 断言收到的 filter）；page 超界 → 空 list + 真实 total；
    // 附带断言领域模型 → DTO 映射（camelCase、时间 "yyyy-MM-dd HH:mm:ss"、status Int、category 枚举名）
    @Test
    fun listDelegatesFilterAndHandlesPageOverflow() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-11-04T04:00:00Z"))
        val repository = FakeFeedbackRepository()
        val service = FeedbackService(repository, AllowAllRateLimiter(), clock)
        val sample = Feedback(
            id = 42L,
            title = "启动崩溃",
            content = "点击图标后闪退",
            category = FeedbackCategory.BUG,
            appName = "launcher",
            appVersion = "1.2.3",
            deviceModel = null,
            osVersion = "15",
            deviceId = "device-1",
            contact = null,
            status = FeedbackStatus.DONE,
            images = emptyList(),
            createdAt = LocalDateTime.of(2026, 11, 4, 12, 0, 0),
            updatedAt = LocalDateTime.of(2026, 11, 4, 13, 30, 5),
        )
        repository.listResult = 3L to listOf(sample)
        val filter = FeedbackListFilter(
            appName = "launcher",
            appVersion = "1.2.3",
            categories = listOf(FeedbackCategory.BUG, FeedbackCategory.SUGGESTION),
            status = FeedbackStatus.DONE,
            startAt = LocalDateTime.of(2026, 11, 1, 0, 0, 0),
            endAt = LocalDateTime.of(2026, 11, 30, 23, 59, 59),
            keyword = "崩溃",
        )

        val response = service.list(filter, page = 2, pageSize = 10)

        // 全字段委托：repo 收到的 filter 与调用完全一致（data class 逐字段相等），分页参数原样透传
        assertEquals(listOf(Triple(filter, 2, 10)), repository.listQueries)
        assertEquals(3L, response.total)
        assertEquals(2, response.page)
        assertEquals(10, response.pageSize)
        val item: FeedbackListItem = response.list.single()
        assertEquals(42L, item.id)
        assertEquals("启动崩溃", item.title)
        assertEquals("点击图标后闪退", item.content)
        assertEquals("BUG", item.category)
        assertEquals("launcher", item.appName)
        assertEquals("1.2.3", item.appVersion)
        assertEquals(null, item.deviceModel)
        assertEquals("15", item.osVersion)
        assertEquals("device-1", item.deviceId)
        assertEquals(null, item.contact)
        assertEquals(1, item.status)
        assertEquals(emptyList<String>(), item.images)
        assertEquals("2026-11-04 12:00:00", item.createdAt)
        assertEquals("2026-11-04 13:30:05", item.updatedAt)

        // page 超界：repo（真实实现 offset 越界自然无行）返回空 list，service 透传真实 total 不二次加工
        repository.listResult = 3L to emptyList()
        val overflowFilter = filter.copy(keyword = null)
        val overflow = service.list(overflowFilter, page = 99, pageSize = 20)

        assertEquals(listOf(Triple(filter, 2, 10), Triple(overflowFilter, 99, 20)), repository.listQueries)
        assertEquals(3L, overflow.total)
        assertEquals(emptyList<FeedbackListItem>(), overflow.list)
        assertEquals(99, overflow.page)
        assertEquals(20, overflow.pageSize)
    }

    @Test
    fun updateStatusRejectsInvalidValue() = runBlocking {
        // 行为 6.1：status 传 0/1 之外（含负数）或缺字段 → 40001，message 指明字段与原因
        val clock = MutableClock(Instant.parse("2026-11-04T04:00:00Z"))
        val repository = FakeFeedbackRepository()
        val service = FeedbackService(repository, AllowAllRateLimiter(), clock)

        val outOfDomain = assertFailsWith<ApiException> {
            service.updateStatus("42", UpdateStatusRequest(status = 2))
        }
        assertEquals(ErrorCode.BAD_REQUEST, outOfDomain.code)
        assertEquals("status: 仅允许 0 或 1", outOfDomain.message)

        val negative = assertFailsWith<ApiException> {
            service.updateStatus("42", UpdateStatusRequest(status = -1))
        }
        assertEquals(ErrorCode.BAD_REQUEST, negative.code)
        assertEquals("status: 仅允许 0 或 1", negative.message)

        val missing = assertFailsWith<ApiException> {
            service.updateStatus("42", UpdateStatusRequest(status = null))
        }
        assertEquals(ErrorCode.BAD_REQUEST, missing.code)
        assertEquals("status: 必填", missing.message)

        assertEquals(0, repository.statusUpdates.size) // 非法值在触达仓储前短路
        assertEquals(0, repository.deletedIds.size)
    }

    @Test
    fun updateStatusTogglesAndRefreshesUpdatedAt() = runBlocking {
        // 行为 6.2：0↔1 双向更新成功；updatedAt 刷新为注入 Clock 当前值且两次更新间递增
        val clock = MutableClock(Instant.parse("2026-11-04T04:00:00Z")) // 上海 12:00:00
        val repository = FakeFeedbackRepository()
        val service = FeedbackService(repository, AllowAllRateLimiter(), clock)
        service.submit(validRequest()) // → id 42，PENDING，updatedAt=12:00:00

        service.updateStatus("42", UpdateStatusRequest(status = 1)) // PENDING → DONE

        assertEquals(
            listOf(Triple(42L, FeedbackStatus.DONE, LocalDateTime.of(2026, 11, 4, 12, 0, 0))),
            repository.statusUpdates,
        )
        assertEquals(FeedbackStatus.DONE, repository.findById(42)?.status)

        clock.advanceSeconds(90)
        service.updateStatus("42", UpdateStatusRequest(status = 0)) // DONE → PENDING

        val (_, secondStatus, secondUpdatedAt) = repository.statusUpdates[1]
        assertEquals(FeedbackStatus.PENDING, secondStatus)
        assertEquals(LocalDateTime.of(2026, 11, 4, 12, 1, 30), secondUpdatedAt) // 注入 Clock 当前值
        assertTrue(secondUpdatedAt > repository.statusUpdates[0].third) // 两次更新 updatedAt 递增
        assertEquals(secondUpdatedAt, repository.findById(42)?.updatedAt) // 落库行同步刷新
    }

    @Test
    fun updateStatusMissingIdThrows40401() = runBlocking {
        // 行为 6.3：id 不存在 → 40401；仓储收到完整参数后未命中才抛
        val clock = MutableClock(Instant.parse("2026-11-04T04:00:00Z"))
        val repository = FakeFeedbackRepository()
        val service = FeedbackService(repository, AllowAllRateLimiter(), clock)

        val error = assertFailsWith<ApiException> {
            service.updateStatus("999", UpdateStatusRequest(status = 1))
        }

        assertEquals(ErrorCode.NOT_FOUND, error.code)
        assertEquals("反馈不存在", error.message)
        assertEquals(
            listOf(Triple(999L, FeedbackStatus.DONE, LocalDateTime.of(2026, 11, 4, 12, 0, 0))),
            repository.statusUpdates,
        )
    }

    @Test
    fun nonNumericIdRejected() = runBlocking {
        // 行为 6.4：id 非数字（或缺失）→ 40001，update/delete 同口径，均不触达仓储
        val clock = MutableClock(Instant.parse("2026-11-04T04:00:00Z"))
        val repository = FakeFeedbackRepository()
        val service = FeedbackService(repository, AllowAllRateLimiter(), clock)

        val update = assertFailsWith<ApiException> {
            service.updateStatus("abc", UpdateStatusRequest(status = 1))
        }
        assertEquals(ErrorCode.BAD_REQUEST, update.code)
        assertEquals("id: 必须为数字", update.message)

        val nullId = assertFailsWith<ApiException> { service.delete(null) }
        assertEquals(ErrorCode.BAD_REQUEST, nullId.code)
        assertEquals("id: 必须为数字", nullId.message)

        val partialNumeric = assertFailsWith<ApiException> { service.delete("12x") }
        assertEquals(ErrorCode.BAD_REQUEST, partialNumeric.code)
        assertEquals("id: 必须为数字", partialNumeric.message)

        assertEquals(0, repository.statusUpdates.size)
        assertEquals(0, repository.deletedIds.size)
    }

    @Test
    fun deleteBehaves() = runBlocking {
        // 行为 6.5：删除存在 id → 无异常（route 封装 data=null）；不存在 → 40401
        val clock = MutableClock(Instant.parse("2026-11-04T04:00:00Z"))
        val repository = FakeFeedbackRepository()
        val service = FeedbackService(repository, AllowAllRateLimiter(), clock)
        service.submit(validRequest()) // → id 42

        service.delete("42")

        assertEquals(listOf(42L), repository.deletedIds)
        assertEquals(null, repository.findById(42)) // 物理删除，非软删除

        val error = assertFailsWith<ApiException> { service.delete("42") } // 已不存在
        assertEquals(ErrorCode.NOT_FOUND, error.code)
        assertEquals("反馈不存在", error.message)
        assertEquals(listOf(42L, 42L), repository.deletedIds)
    }
}
