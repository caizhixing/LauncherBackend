package com.caizhixing.feedback.model

/**
 * 反馈分类（PRD §2.2 契约值域）；DB 存枚举名字符串，JSON 契约同名。
 */
enum class FeedbackCategory { BUG, EXPERIENCE, SUGGESTION, OTHER }
