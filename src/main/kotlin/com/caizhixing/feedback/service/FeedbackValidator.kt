package com.caizhixing.feedback.service

import com.caizhixing.feedback.model.FeedbackCategory
import com.caizhixing.feedback.model.dto.SubmitFeedbackRequest

/**
 * 提交反馈校验（PRD §2.5 校验表，round1 契约对齐）：
 * title/content/appName/appVersion/deviceId 必填（缺失/null/空白同判）且限长，
 * category 必填且限枚举值域，deviceModel/osVersion/contact 可选（null 放行）仅限长。
 * message 逐字段按 "字段: 原因" 产出（如 "title: 必填"），返回首条错误（sealed）；
 * 校验通过时产出 ValidatedFeedback（必填非空、category 已解析为枚举），service 免空断言。
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
    private val CATEGORY_BY_NAME: Map<String, FeedbackCategory> =
        FeedbackCategory.entries.associateBy { it.name }

    fun validate(request: SubmitFeedbackRequest): ValidationResult {
        val title = request.title
        if (title == null || title.isBlank()) return ValidationResult.Invalid(missing("title"))
        if (title.length > TITLE_MAX_LENGTH) return ValidationResult.Invalid(tooLong("title", TITLE_MAX_LENGTH))

        val content = request.content
        if (content == null || content.isBlank()) return ValidationResult.Invalid(missing("content"))
        if (content.length > CONTENT_MAX_LENGTH) {
            return ValidationResult.Invalid(tooLong("content", CONTENT_MAX_LENGTH))
        }

        val category = request.category
        if (category == null || category.isBlank()) return ValidationResult.Invalid(missing("category"))
        val resolvedCategory = CATEGORY_BY_NAME[category]
            ?: return ValidationResult.Invalid(
                "category: 必须是 ${CATEGORY_VALUES.joinToString("/")} 之一",
            )

        val appName = request.appName
        if (appName == null || appName.isBlank()) return ValidationResult.Invalid(missing("appName"))
        if (appName.length > APP_NAME_MAX_LENGTH) {
            return ValidationResult.Invalid(tooLong("appName", APP_NAME_MAX_LENGTH))
        }

        val appVersion = request.appVersion
        if (appVersion == null || appVersion.isBlank()) return ValidationResult.Invalid(missing("appVersion"))
        if (appVersion.length > APP_VERSION_MAX_LENGTH) {
            return ValidationResult.Invalid(tooLong("appVersion", APP_VERSION_MAX_LENGTH))
        }

        val deviceId = request.deviceId
        if (deviceId == null || deviceId.isBlank()) return ValidationResult.Invalid(missing("deviceId"))
        if (deviceId.length > DEVICE_ID_MAX_LENGTH) {
            return ValidationResult.Invalid(tooLong("deviceId", DEVICE_ID_MAX_LENGTH))
        }

        val deviceModel = request.deviceModel
        if (deviceModel != null && deviceModel.length > DEVICE_MODEL_MAX_LENGTH) {
            return ValidationResult.Invalid(tooLong("deviceModel", DEVICE_MODEL_MAX_LENGTH))
        }

        val osVersion = request.osVersion
        if (osVersion != null && osVersion.length > OS_VERSION_MAX_LENGTH) {
            return ValidationResult.Invalid(tooLong("osVersion", OS_VERSION_MAX_LENGTH))
        }

        val contact = request.contact
        if (contact != null && contact.length > CONTACT_MAX_LENGTH) {
            return ValidationResult.Invalid(tooLong("contact", CONTACT_MAX_LENGTH))
        }

        return ValidationResult.Valid(
            ValidatedFeedback(
                title = title,
                content = content,
                category = resolvedCategory,
                appName = appName,
                appVersion = appVersion,
                deviceModel = deviceModel,
                osVersion = osVersion,
                deviceId = deviceId,
                contact = contact,
            ),
        )
    }

    private fun missing(fieldName: String): String = "$fieldName: 必填"

    private fun tooLong(fieldName: String, maxLength: Int): String =
        "$fieldName: 长度不能超过 $maxLength 字符"
}
