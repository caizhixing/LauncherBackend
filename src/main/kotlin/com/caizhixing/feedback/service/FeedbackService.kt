package com.caizhixing.feedback.service

import com.caizhixing.feedback.model.ApiException
import com.caizhixing.feedback.model.ErrorCode
import com.caizhixing.feedback.model.Feedback
import com.caizhixing.feedback.model.FeedbackListFilter
import com.caizhixing.feedback.model.FeedbackStatus
import com.caizhixing.feedback.model.dto.FeedbackListItem
import com.caizhixing.feedback.model.dto.FeedbackListResponse
import com.caizhixing.feedback.model.dto.SubmitFeedbackRequest
import com.caizhixing.feedback.model.dto.SubmitFeedbackResponse
import com.caizhixing.feedback.repository.FeedbackRepository
import com.caizhixing.feedback.util.AppTime
import com.caizhixing.feedback.util.RateLimitResult
import com.caizhixing.feedback.util.RateLimiter
import java.time.Clock

/**
 * 提交反馈 + 反馈列表查询（PRD §2.5，issue #5 追加 list）。
 * DB 事务失败自然冒泡，由 StatusPages 兜底 50000（DECISIONS.md #DB故障降级实现）。
 */
class FeedbackService(
    private val repository: FeedbackRepository,
    private val rateLimiter: RateLimiter,
    private val clock: Clock,
) {
    suspend fun submit(request: SubmitFeedbackRequest): SubmitFeedbackResponse {
        // 校验通过即拿到强类型输入（必填非空、category 已解析），无需二次空断言
        val input = when (val validation = FeedbackValidator.validate(request)) {
            is ValidationResult.Invalid -> throw ApiException(ErrorCode.BAD_REQUEST, validation.message)
            is ValidationResult.Valid -> validation.input
        }
        when (rateLimiter.tryAcquire(input.deviceId)) {
            is RateLimitResult.Denied -> throw ApiException(ErrorCode.RATE_LIMITED, RATE_LIMITED_MESSAGE)
            RateLimitResult.Allowed -> Unit
        }
        val now = AppTime.now(clock)
        val feedback = Feedback(
            id = null,
            title = input.title,
            content = input.content,
            category = input.category,
            appName = input.appName,
            appVersion = input.appVersion,
            deviceModel = input.deviceModel,
            osVersion = input.osVersion,
            deviceId = input.deviceId,
            contact = input.contact,
            status = FeedbackStatus.PENDING,
            images = emptyList(),
            createdAt = now,
            updatedAt = now,
        )
        return SubmitFeedbackResponse(feedbackId = repository.insert(feedback))
    }

    /**
     * 列表查询（PRD §2.5，行为 5.7）：全字段委托 repository（筛选/分页透传，不二次加工）；
     * page 超界由 repository 返回空 list，service 透传真实 total。排序固定 createdAt DESC 在
     * repository 实现层保证（行为 5.8）。
     */
    suspend fun list(filter: FeedbackListFilter, page: Int, pageSize: Int): FeedbackListResponse {
        val (total, feedbacks) = repository.findTotalAndList(filter, page, pageSize)
        return FeedbackListResponse(
            total = total,
            page = page,
            pageSize = pageSize,
            list = feedbacks.map { it.toListItem() },
        )
    }

    private fun Feedback.toListItem(): FeedbackListItem = FeedbackListItem(
        // 列表行必来自数据库，id 由行值填充不可能为 null；破坏不变式时按 50000 冒泡而非静默丢弃
        id = requireNotNull(id) { "列表查询结果缺少 id" },
        title = title,
        content = content,
        category = category.name,
        appName = appName,
        appVersion = appVersion,
        deviceModel = deviceModel,
        osVersion = osVersion,
        deviceId = deviceId,
        contact = contact,
        status = status.value,
        images = images,
        createdAt = AppTime.format(createdAt),
        updatedAt = AppTime.format(updatedAt),
    )

    private companion object {
        const val RATE_LIMITED_MESSAGE: String = "提交过于频繁，请稍后再试"
    }
}
