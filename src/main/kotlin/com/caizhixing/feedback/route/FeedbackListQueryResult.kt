package com.caizhixing.feedback.route

import com.caizhixing.feedback.model.FeedbackListFilter

/**
 * 列表 Query 参数解析结果（sealed，禁 Boolean 返回；对齐 service 层 ValidationResult 范式）。
 * Valid 携带解析后的筛选条件与分页参数（page/pageSize 已定默认值），route 无需二次判空；
 * Invalid 携带 40001 message（"字段: 原因"）。
 */
sealed interface FeedbackListQueryResult {
    data class Valid(
        val filter: FeedbackListFilter,
        val page: Int,
        val pageSize: Int,
    ) : FeedbackListQueryResult

    data class Invalid(val message: String) : FeedbackListQueryResult
}
