package com.caizhixing.feedback.model.dto

import kotlinx.serialization.Serializable

// 字段缺省空串：请求体缺字段与显式空值归一为「空」，由 service 层校验统一拒绝（行为 3.3）
@Serializable
data class LoginRequest(
    val username: String = "",
    val password: String = "",
)
