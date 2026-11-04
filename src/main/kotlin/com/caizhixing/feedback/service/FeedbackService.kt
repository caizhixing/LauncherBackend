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
import com.caizhixing.feedback.model.dto.UpdateStatusRequest
import com.caizhixing.feedback.repository.FeedbackRepository
import com.caizhixing.feedback.util.AppTime
import com.caizhixing.feedback.util.RateLimitResult
import com.caizhixing.feedback.util.RateLimiter
import java.time.Clock

/**
 * 提交反馈（PRD §2.5）：校验 → 限流（同 deviceId 1/分 + 5/天，DECISIONS.md #限流语义）
 * → 服务端补全（status=PENDING、images=[]、createdAt=updatedAt 同一时钟值）→ 落库返回自增 id。
 * 列表查询（PRD §2.5，issue #5 追加 list）。
 * 标记状态/删除（#6）：id 与 status 解析校验 40001 → 仓储未命中 40401；
 * updatedAt 刷新取注入 Clock 当前值（两次更新间推进时钟即递增）。
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

    /** 标记处理状态（PRD §2.5）：id/status 解析校验 40001 → 仓储未命中 40401；updatedAt 取注入 Clock 当前值。 */
    suspend fun updateStatus(id: String?, request: UpdateStatusRequest) {
        val feedbackId = parseFeedbackId(id)
        val status = statusFromRequest(request.status)
        val updatedAt = AppTime.now(clock)
        if (!repository.updateStatus(feedbackId, status, updatedAt)) {
            throw ApiException(ErrorCode.NOT_FOUND, NOT_FOUND_MESSAGE)
        }
    }

    /** 删除反馈（PRD §2.5 物理删除，非软删除）：id 解析校验 40001 → 仓储未命中 40401。 */
    suspend fun delete(id: String?) {
        val feedbackId = parseFeedbackId(id)
        if (!repository.delete(feedbackId)) {
            throw ApiException(ErrorCode.NOT_FOUND, NOT_FOUND_MESSAGE)
        }
    }

    private fun statusFromRequest(value: Int?): FeedbackStatus = when (value) {
        FeedbackStatus.PENDING.value -> FeedbackStatus.PENDING
        FeedbackStatus.DONE.value -> FeedbackStatus.DONE
        null -> throw ApiException(ErrorCode.BAD_REQUEST, "status: 必填")
        else -> throw ApiException(ErrorCode.BAD_REQUEST, "status: 仅允许 0 或 1")
    }

    private companion object {
        const val RATE_LIMITED_MESSAGE: String = "提交过于频繁，请稍后再试"
        const val NOT_FOUND_MESSAGE: String = "反馈不存在"
    }
}
