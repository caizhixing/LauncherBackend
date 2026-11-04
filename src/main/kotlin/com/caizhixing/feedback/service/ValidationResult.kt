package com.caizhixing.feedback.service

/**
 * 提交反馈字段校验结果（sealed，禁 Boolean 返回）。
 * Valid 携带校验后输入（必填非空、category 已解析），service 无需二次空断言。
 */
sealed interface ValidationResult {
    data class Valid(val input: ValidatedFeedback) : ValidationResult

    data class Invalid(val message: String) : ValidationResult
}
