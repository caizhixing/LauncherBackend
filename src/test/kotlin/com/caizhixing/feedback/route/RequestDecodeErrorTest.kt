package com.caizhixing.feedback.route

import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * round1 blocker1：反序列化失败兜底 message 逐分支覆盖——
 * 缺字段列出字段名 / 类型不匹配透出原因（含 $.字段 路径）/ 无序列化异常按非法 JSON 归类。
 */
class RequestDecodeErrorTest {

    @Test
    fun missingFieldsAreNamedInMessage() {
        val cause = IllegalStateException(
            "wrap",
            MissingFieldException(listOf("title", "content"), "SubmitFeedbackRequest"),
        )

        assertEquals("缺少必填字段: title、content", requestDecodeErrorMessage(cause))
    }

    @Test
    fun typeMismatchKeepsSerializationReason() {
        val reason = "Expected string but was NUMBER at path: \$.title"
        val cause = IllegalStateException("wrap", SerializationException(reason))

        assertEquals("请求体格式错误：$reason", requestDecodeErrorMessage(cause))
    }

    @Test
    fun unknownFailureFallsBackToIllegalJsonMessage() {
        assertEquals("请求体格式错误：非法 JSON", requestDecodeErrorMessage(IllegalStateException("nope")))
    }

    @Test
    fun nullSerializationMessageStillNamesReason() {
        val cause = IllegalStateException("wrap", SerializationException())

        assertEquals("请求体格式错误：反序列化失败", requestDecodeErrorMessage(cause))
    }
}
