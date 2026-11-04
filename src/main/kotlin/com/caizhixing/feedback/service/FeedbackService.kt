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
        when (val validation = FeedbackValidator.validate(request)) {
            is ValidationResult.Invalid -> throw ApiException(ErrorCode.BAD_REQUEST, validation.message)
            ValidationResult.Valid -> Unit
        }
        when (rateLimiter.tryAcquire(request.deviceId)) {
            is RateLimitResult.Denied -> throw ApiException(ErrorCode.RATE_LIMITED, RATE_LIMITED_MESSAGE)
            RateLimitResult.Allowed -> Unit
        }
        val now = AppTime.now(clock)
        val feedback = Feedback(
            id = null,
            title = request.title,
            content = request.content,
            category = FeedbackCategory.valueOf(request.category),
            appName = request.appName,
            appVersion = request.appVersion,
            deviceModel = request.deviceModel,
            osVersion = request.osVersion,
            deviceId = request.deviceId,
            contact = request.contact,
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
