package com.caizhixing.feedback.plugin

import com.caizhixing.feedback.model.ApiException
import com.caizhixing.feedback.model.ErrorCode
import com.caizhixing.feedback.model.dto.ApiResponse
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals

class ErrorMappingTest {

    @Test
    fun apiExceptionCodesMapToHttpStatuses() {
        assertEquals(HttpStatusCode.BadRequest, httpStatusFor(ErrorCode.BAD_REQUEST))
        assertEquals(HttpStatusCode.Unauthorized, httpStatusFor(ErrorCode.UNAUTHORIZED))
        assertEquals(HttpStatusCode.Unauthorized, httpStatusFor(ErrorCode.TOKEN_EXPIRED))
        assertEquals(HttpStatusCode.Unauthorized, httpStatusFor(ErrorCode.BAD_CREDENTIALS))
        assertEquals(HttpStatusCode.NotFound, httpStatusFor(ErrorCode.NOT_FOUND))
        assertEquals(HttpStatusCode.TooManyRequests, httpStatusFor(ErrorCode.RATE_LIMITED))
        assertEquals(HttpStatusCode.InternalServerError, httpStatusFor(ErrorCode.INTERNAL))
    }

    @Test
    fun unknownCodeFallsBackToInternalServerError() {
        assertEquals(HttpStatusCode.InternalServerError, httpStatusFor(-1))
    }

    @Test
    fun apiExceptionProducesUnifiedErrorBody() {
        val cause = ApiException(ErrorCode.RATE_LIMITED, "提交过于频繁，请稍后再试")
        val body = errorResponse(cause)
        assertEquals(ErrorCode.RATE_LIMITED, body.code)
        assertEquals("提交过于频繁，请稍后再试", body.message)
        assertEquals(JsonNull, body.data)
    }

    @Test
    fun uncaughtExceptionMapsToInternalError() {
        val body = errorResponse(IllegalStateException("boom"))
        assertEquals(ErrorCode.INTERNAL, body.code)
        assertEquals("服务器内部错误", body.message)
        assertEquals(JsonNull, body.data)
    }

    @Test
    fun errorBodyKeepsExplicitNullDataUnderContractJson() {
        val body = errorResponse(ApiException(ErrorCode.BAD_REQUEST, "参数错误"))
        val encoded = JsonConfig.json.encodeToString(
            ApiResponse.serializer(JsonElement.serializer()),
            body,
        )
        assertEquals("""{"code":40001,"message":"参数错误","data":null}""", encoded)
    }

    @Test
    fun successBodyCarriesDataObject() {
        val encoded = JsonConfig.json.encodeToString(
            ApiResponse.serializer(Unit.serializer()),
            ApiResponse(ErrorCode.OK, "success", Unit),
        )
        assertEquals("""{"code":0,"message":"success","data":{}}""", encoded)
    }
}
