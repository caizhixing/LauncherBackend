package com.caizhixing.feedback.model.dto

import kotlinx.serialization.Serializable

@Serializable
data class LoginResponse(
    val token: String,
    val expiresIn: Long,
)
