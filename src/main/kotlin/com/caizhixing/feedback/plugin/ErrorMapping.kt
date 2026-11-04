package com.caizhixing.feedback.plugin

import com.caizhixing.feedback.model.ApiException
import com.caizhixing.feedback.model.ErrorCode
import com.caizhixing.feedback.model.dto.ApiResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

fun httpStatusFor(code: Int): HttpStatusCode = when (code) {
    ErrorCode.BAD_REQUEST -> HttpStatusCode.BadRequest
    ErrorCode.UNAUTHORIZED, ErrorCode.TOKEN_EXPIRED, ErrorCode.BAD_CREDENTIALS -> HttpStatusCode.Unauthorized
    ErrorCode.NOT_FOUND -> HttpStatusCode.NotFound
    ErrorCode.RATE_LIMITED -> HttpStatusCode.TooManyRequests
    else -> HttpStatusCode.InternalServerError
}

fun errorResponse(cause: Throwable): ApiResponse<JsonElement> {
    val code: Int
    val message: String
    if (cause is ApiException) {
        code = cause.code
        message = cause.message
    } else {
        code = ErrorCode.INTERNAL
        message = "服务器内部错误"
    }
    // data 传 JsonNull 实例而非 null 值：explicitNulls = false 只省略值为 null 的字段，
    // JsonNull 会按 JSON 字面量输出，保证统一响应体保留 "data": null（PRD §2.3）
    return ApiResponse(code = code, message = message, data = JsonNull)
}

fun Application.installErrorMapping() {
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respond(httpStatusFor(cause.code), errorResponse(cause))
        }
        exception<Throwable> { call, cause ->
            call.respond(httpStatusFor(ErrorCode.INTERNAL), errorResponse(cause))
        }
    }
}
