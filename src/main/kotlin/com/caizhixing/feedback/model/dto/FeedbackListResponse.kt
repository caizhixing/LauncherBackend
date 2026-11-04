package com.caizhixing.feedback.model.dto

import kotlinx.serialization.Serializable

/**
 * GET /api/feedback/list 成功响应 data（PRD §2.4）。
 * total 为满足筛选条件的真实总数（与分页无关）；page 超界时 list 为空、total 仍为真实值。
 */
@Serializable
data class FeedbackListResponse(
    val total: Long,
    val page: Int,
    val pageSize: Int,
    val list: List<FeedbackListItem>,
)
