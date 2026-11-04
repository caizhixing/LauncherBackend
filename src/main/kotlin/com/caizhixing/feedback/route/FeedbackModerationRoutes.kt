package com.caizhixing.feedback.route

import com.caizhixing.feedback.model.ApiException
import com.caizhixing.feedback.model.ErrorCode
import com.caizhixing.feedback.model.dto.ApiResponse
import com.caizhixing.feedback.model.dto.UpdateStatusRequest
import com.caizhixing.feedback.plugin.AUTH_PROVIDER_NAME
import com.caizhixing.feedback.service.FeedbackService
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.put
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import org.koin.ktor.ext.get

/**
 * PUT /api/feedback/{id}/status 与 DELETE /api/feedback/{id}（鉴权路由，PRD §2.3）。
 * 与 #5 的列表路由分文件分函数（PRD §3 并行约定），经 feedbackRoutes() 一行挂载。
 * route 只取原始路径参数与请求体并封装响应；id/status 校验与 40401 判定全部下沉 service。
 * 成功 data 恒为 null：传 JsonNull 实例而非 null 值，保证 explicitNulls = false 下
 * 仍输出 "data": null（PRD §2.3，同 ErrorMapping 的处理方式）。
 */
fun Route.feedbackModerationRoutes() {
    authenticate(AUTH_PROVIDER_NAME) {
        put("/api/feedback/{id}/status") {
            val service: FeedbackService = call.get()
            val request = try {
                call.receive<UpdateStatusRequest>()
            } catch (cause: BadRequestException) {
                throw ApiException(ErrorCode.BAD_REQUEST, requestDecodeErrorMessage(cause))
            }
            service.updateStatus(call.parameters["id"], request)
            call.respond(nullDataSuccess())
        }
        delete("/api/feedback/{id}") {
            val service: FeedbackService = call.get()
            service.delete(call.parameters["id"])
            call.respond(nullDataSuccess())
        }
    }
}

private fun nullDataSuccess(): ApiResponse<JsonElement> =
    ApiResponse(code = ErrorCode.OK, message = "success", data = JsonNull)
