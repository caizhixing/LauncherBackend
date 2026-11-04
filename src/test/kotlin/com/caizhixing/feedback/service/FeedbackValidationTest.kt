package com.caizhixing.feedback.service

import com.caizhixing.feedback.model.FeedbackCategory
import com.caizhixing.feedback.model.dto.SubmitFeedbackRequest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 行为 4.2：校验矩阵逐条 40001（参数化）+ 边界值（0/1/50/51、1000/1001、
 * 64/65、100/101、20/21）与全枚举/非枚举。
 */
class FeedbackValidationTest {

    data class InvalidCase(val name: String, val request: SubmitFeedbackRequest, val field: String) {
        override fun toString(): String = name
    }

    companion object {
        private fun base(): SubmitFeedbackRequest = SubmitFeedbackRequest(
            title = "t",
            content = "c",
            category = "BUG",
            appName = "app",
            appVersion = "1.0",
            deviceId = "device",
        )

        private fun text(length: Int): String = "x".repeat(length)

        private fun case(name: String, request: SubmitFeedbackRequest, field: String) =
            InvalidCase(name, request, field)

        @JvmStatic
        fun invalidCases(): List<InvalidCase> = listOf(
            case("title 空", base().copy(title = ""), "title"),
            case("title 纯空白", base().copy(title = "   "), "title"),
            case("title 51 字", base().copy(title = text(51)), "title"),
            case("content 空", base().copy(content = ""), "content"),
            case("content 纯空白", base().copy(content = " "), "content"),
            case("content 1001 字", base().copy(content = text(1001)), "content"),
            case("category 非枚举", base().copy(category = "FOO"), "category"),
            case("category 空", base().copy(category = ""), "category"),
            case("category 小写不匹配", base().copy(category = "bug"), "category"),
            case("appName 空", base().copy(appName = ""), "appName"),
            case("appName 51 字", base().copy(appName = text(51)), "appName"),
            case("appVersion 空", base().copy(appVersion = ""), "appVersion"),
            case("appVersion 21 字", base().copy(appVersion = text(21)), "appVersion"),
            case("deviceId 空", base().copy(deviceId = ""), "deviceId"),
            case("deviceId 65 字", base().copy(deviceId = text(65)), "deviceId"),
            case("deviceModel 101 字", base().copy(deviceModel = text(101)), "deviceModel"),
            case("osVersion 21 字", base().copy(osVersion = text(21)), "osVersion"),
            case("contact 101 字", base().copy(contact = text(101)), "contact"),
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCases")
    fun invalidFieldRejected(case: InvalidCase) {
        val result = FeedbackValidator.validate(case.request)

        val invalid = assertIs<ValidationResult.Invalid>(result, "case=${case.name} 应为 Invalid")
        assertTrue(
            invalid.message.startsWith(case.field),
            "case=${case.name} 的 message 应以字段 ${case.field} 开头，实际: ${invalid.message}",
        )
    }

    @Test
    fun boundaryValuesAccepted() {
        val minBound = SubmitFeedbackRequest(
            title = "1",
            content = "1",
            category = "OTHER",
            appName = "1",
            appVersion = "1",
            deviceId = "1",
        )
        assertIs<ValidationResult.Valid>(FeedbackValidator.validate(minBound))

        val maxBound = SubmitFeedbackRequest(
            title = text(FeedbackValidator.TITLE_MAX_LENGTH),
            content = text(FeedbackValidator.CONTENT_MAX_LENGTH),
            category = "SUGGESTION",
            appName = text(FeedbackValidator.APP_NAME_MAX_LENGTH),
            appVersion = text(FeedbackValidator.APP_VERSION_MAX_LENGTH),
            deviceId = text(FeedbackValidator.DEVICE_ID_MAX_LENGTH),
            deviceModel = text(FeedbackValidator.DEVICE_MODEL_MAX_LENGTH),
            osVersion = text(FeedbackValidator.OS_VERSION_MAX_LENGTH),
            contact = text(FeedbackValidator.CONTACT_MAX_LENGTH),
        )
        assertIs<ValidationResult.Valid>(FeedbackValidator.validate(maxBound))

        val optionalsOmitted = base()
        assertIs<ValidationResult.Valid>(FeedbackValidator.validate(optionalsOmitted))
    }

    @Test
    fun allEnumCategoriesAccepted() {
        FeedbackCategory.entries.forEach { category ->
            assertIs<ValidationResult.Valid>(FeedbackValidator.validate(base().copy(category = category.name)))
        }
    }
}
