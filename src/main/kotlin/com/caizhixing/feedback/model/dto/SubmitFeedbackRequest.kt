package com.caizhixing.feedback.model.dto

import kotlinx.serialization.Serializable

/**
 * POST /api/feedback 请求体（PRD §2.4）。
 * category 保持 String：非枚举值须映射 40001（若用枚举反序列化会变 50000），
 * 枚举校验下沉 service（FeedbackValidator）。
 */
@Serializable
data class SubmitFeedbackRequest(
    val title: String,
    val content: String,
    val category: String,
    val appName: String,
    val appVersion: String,
    val deviceId: String,
    val deviceModel: String? = null,
    val osVersion: String? = null,
    val contact: String? = null,
)
