package com.caizhixing.feedback.service

import com.caizhixing.feedback.model.ApiException
import com.caizhixing.feedback.model.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 行为 6.4 支撑：id 解析纯函数全分支覆盖（issue #6 要求解析纯函数 100% 行覆盖）。
 */
class FeedbackIdParserTest {

    @Test
    fun numericStringsParseToLong() {
        assertEquals(42L, parseFeedbackId("42"))
        assertEquals(0L, parseFeedbackId("0"))
        assertEquals(-1L, parseFeedbackId("-1")) // 负数是合法数字，未命中由仓储判 40401
        assertEquals(Long.MAX_VALUE, parseFeedbackId(Long.MAX_VALUE.toString()))
    }

    @Test
    fun nonNumericOrNullIdsRejectedWith40001() {
        // toLongOrNull 语义：不 trim、不接受小数与前后缀字符；全角数字属合法数字（未命中走 40401）
        listOf(null, "", "abc", "12x", " 42", "3.5").forEach { raw ->
            val error = assertFailsWith<ApiException> { parseFeedbackId(raw) }
            assertEquals(ErrorCode.BAD_REQUEST, error.code, "raw=$raw")
            assertEquals("id: 必须为数字", error.message, "raw=$raw")
        }
    }
}
