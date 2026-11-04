package com.caizhixing.feedback.model

import java.time.LocalDateTime

/**
 * 列表筛选条件（PRD §2.4 冻结签名，issue #5）。
 * 字段全部可选：null 表示不筛选。startAt/endAt 已按 PRD §2.5 折算为含端点全天的
 * 上海墙钟区间 [当日 00:00:00, 当日 23:59:59]；categories 非空（解析器保证任一段
 * 非法即 40001，不产出空列表）；keyword 已 trim 且非空。
 */
data class FeedbackListFilter(
    val appName: String?,
    val appVersion: String?,
    val categories: List<FeedbackCategory>?,
    val status: FeedbackStatus?,
    val startAt: LocalDateTime?,
    val endAt: LocalDateTime?,
    val keyword: String?,
)
