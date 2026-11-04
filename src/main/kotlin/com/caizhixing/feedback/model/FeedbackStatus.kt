package com.caizhixing.feedback.model

/**
 * 反馈处理状态（PRD §2.2 契约）：JSON 契约为 Int，仅允许 0/1；DB 列 TINYINT。
 */
enum class FeedbackStatus(val value: Int) {
    PENDING(0),
    DONE(1),
}
