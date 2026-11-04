package com.caizhixing.feedback.model.dto

import kotlinx.serialization.Serializable

/**
 * POST /api/feedback 请求体（PRD §2.4）。
 * 入参全字段显式可空建模（round1 契约对齐）：缺必填字段不再依赖 kotlinx 反序列化异常兜底，
 * 而是反序列化为 null 后由 FeedbackValidator 逐字段产出 40001 message（如 "title: 必填"）；
 * 未知字段仍按 ignoreUnknownKeys=true 忽略（PRD §6 决策 3）。
 * category 保持 String：非枚举值须映射 40001（若用枚举反序列化会变 50000），枚举校验下沉 service。
 */
@Serializable
data class SubmitFeedbackRequest(
    val title: String? = null,
    val content: String? = null,
    val category: String? = null,
    val appName: String? = null,
    val appVersion: String? = null,
    val deviceId: String? = null,
    val deviceModel: String? = null,
    val osVersion: String? = null,
    val contact: String? = null,
)
