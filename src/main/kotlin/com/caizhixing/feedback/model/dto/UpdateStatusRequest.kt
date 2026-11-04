package com.caizhixing.feedback.model.dto

import kotlinx.serialization.Serializable

/**
 * PUT /api/feedback/{id}/status 请求体（PRD §2.4：status: Int，值域 0/1）。
 * 入参可空建模（同 SubmitFeedbackRequest 的 round1 契约对齐）：缺字段反序列化为 null，
 * 由 service 归一产出 40001（"status: 必填"）；字段类型不匹配仍走解码失败兜底。
 */
@Serializable
data class UpdateStatusRequest(
    val status: Int? = null,
)
