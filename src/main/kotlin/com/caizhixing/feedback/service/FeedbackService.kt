package com.caizhixing.feedback.service

import com.caizhixing.feedback.model.ApiException
import com.caizhixing.feedback.model.ErrorCode
import com.caizhixing.feedback.model.Feedback
import com.caizhixing.feedback.model.FeedbackStatus
import com.caizhixing.feedback.model.dto.SubmitFeedbackRequest
import com.caizhixing.feedback.model.dto.SubmitFeedbackResponse
import com.caizhixing.feedback.repository.FeedbackRepository
import com.caizhixing.feedback.util.AppTime
import com.caizhixing.feedback.util.RateLimitResult
import com.caizhixing.feedback.util.RateLimiter
import java.time.Clock

/**
 * 提交反馈（PRD §2.5）：校验 → 限流（同 deviceId 1/分 + 5/天，DECISIONS.md #限流语义）
 * → 服务端补全（status=PENDING、images=[]、createdAt=updatedAt 同一时钟值）→ 落库返回自增 id。
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

    private companion object {
        const val RATE_LIMITED_MESSAGE: String = "提交过于频繁，请稍后再试"
    }
}
