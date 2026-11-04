package com.caizhixing.feedback.service

import com.caizhixing.feedback.model.FeedbackCategory
import com.caizhixing.feedback.model.dto.SubmitFeedbackRequest

/**
 * 提交反馈校验（PRD §2.5 校验表）：
 * title/content/appName/appVersion/deviceId 必填且限长，category 限枚举值域，
 * deviceModel/osVersion/contact 可选（null 放行）仅限长；返回首条错误（sealed）。
 */
object FeedbackValidator {
    const val TITLE_MAX_LENGTH: Int = 50
    const val CONTENT_MAX_LENGTH: Int = 1000
    const val APP_NAME_MAX_LENGTH: Int = 50
    const val APP_VERSION_MAX_LENGTH: Int = 20
    const val DEVICE_ID_MAX_LENGTH: Int = 64
    const val DEVICE_MODEL_MAX_LENGTH: Int = 100
    const val OS_VERSION_MAX_LENGTH: Int = 20
    const val CONTACT_MAX_LENGTH: Int = 100

    private val CATEGORY_VALUES: List<String> = FeedbackCategory.entries.map { it.name }

    fun validate(request: SubmitFeedbackRequest): ValidationResult {
        val error = requiredError("title", request.title, TITLE_MAX_LENGTH)
            ?: requiredError("content", request.content, CONTENT_MAX_LENGTH)
            ?: categoryError(request.category)
            ?: requiredError("appName", request.appName, APP_NAME_MAX_LENGTH)
            ?: requiredError("appVersion", request.appVersion, APP_VERSION_MAX_LENGTH)
            ?: requiredError("deviceId", request.deviceId, DEVICE_ID_MAX_LENGTH)
            ?: optionalError("deviceModel", request.deviceModel, DEVICE_MODEL_MAX_LENGTH)
            ?: optionalError("osVersion", request.osVersion, OS_VERSION_MAX_LENGTH)
            ?: optionalError("contact", request.contact, CONTACT_MAX_LENGTH)
        return if (error == null) ValidationResult.Valid else ValidationResult.Invalid(error)
    }

    private fun requiredError(fieldName: String, value: String, maxLength: Int): String? = when {
        value.isBlank() -> "$fieldName 不能为空"
        value.length > maxLength -> "$fieldName 长度不能超过 $maxLength 字符"
        else -> null
    }

    private fun optionalError(fieldName: String, value: String?, maxLength: Int): String? = when {
        value == null -> null
        value.length > maxLength -> "$fieldName 长度不能超过 $maxLength 字符"
        else -> null
    }

    private fun categoryError(value: String): String? =
        if (value in CATEGORY_VALUES) null else "category 必须是 ${CATEGORY_VALUES.joinToString("/")} 之一"
}
