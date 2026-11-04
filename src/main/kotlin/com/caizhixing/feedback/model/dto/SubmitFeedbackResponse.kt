package com.caizhixing.feedback.model.dto

import kotlinx.serialization.Serializable

/**
 * POST /api/feedback 成功响应 data（PRD §2.4）。
 */
@Serializable
data class SubmitFeedbackResponse(val feedbackId: Long)
