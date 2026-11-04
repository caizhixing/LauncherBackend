package com.caizhixing.feedback.service

/**
 * 提交反馈字段校验结果（sealed，禁 Boolean 返回）。
 */
sealed interface ValidationResult {
    data object Valid : ValidationResult

    data class Invalid(val message: String) : ValidationResult
}
