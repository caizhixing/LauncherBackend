package com.caizhixing.feedback.model

import java.time.LocalDateTime

/**
 * 反馈领域模型（PRD §2.4 冻结签名）。
 * id 在插入前为 null，由数据库自增生成。
 */
data class Feedback(
    val id: Long?,
    val title: String,
    val content: String,
    val category: FeedbackCategory,
    val appName: String,
    val appVersion: String,
    val deviceModel: String?,
    val osVersion: String?,
    val deviceId: String,
    val contact: String?,
    val status: FeedbackStatus,
    val images: List<String>,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)
