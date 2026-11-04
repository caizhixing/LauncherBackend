package com.caizhixing.feedback.service

import com.caizhixing.feedback.model.FeedbackCategory

/**
 * 提交反馈校验通过后的强类型输入（FeedbackValidator 产出）：
 * 必填字段保证非空、category 已解析为枚举，service 直接消费，无需空断言。
 */
data class ValidatedFeedback(
    val title: String,
    val content: String,
    val category: FeedbackCategory,
    val appName: String,
    val appVersion: String,
    val deviceModel: String?,
    val osVersion: String?,
    val deviceId: String,
    val contact: String?,
)
