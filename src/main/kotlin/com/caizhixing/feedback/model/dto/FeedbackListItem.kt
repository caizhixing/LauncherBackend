package com.caizhixing.feedback.model.dto

import kotlinx.serialization.Serializable

/**
 * GET /api/feedback/list 条目（PRD §2.4）：Feedback 全字段 camelCase。
 * createdAt/updatedAt 为 "yyyy-MM-dd HH:mm:ss"（Asia/Shanghai）字符串（AppTime.format）；
 * category 为枚举名字符串；status 为 Int(0/1)；images 一期恒空数组；
 * deviceModel/osVersion/contact 为 null 时整个字段省略（explicitNulls=false）。
 */
@Serializable
data class FeedbackListItem(
    val id: Long,
    val title: String,
    val content: String,
    val category: String,
    val appName: String,
    val appVersion: String,
    val deviceModel: String? = null,
    val osVersion: String? = null,
    val deviceId: String,
    val contact: String? = null,
    val status: Int,
    val images: List<String> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
)
