package com.caizhixing.feedback.route

import com.caizhixing.feedback.model.ApiException
import com.caizhixing.feedback.model.ErrorCode
import com.caizhixing.feedback.model.dto.ApiResponse
import com.caizhixing.feedback.model.dto.SubmitFeedbackRequest
import com.caizhixing.feedback.model.dto.SubmitFeedbackResponse
import com.caizhixing.feedback.service.FeedbackService
import io.ktor.server.application.call
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.koin.ktor.ext.get

private const val SUCCESS_MESSAGE = "success"

/**
 * POST /api/feedback（公开路由，PRD §2.3）。route 只做参数解析与响应封装；
 * 校验、限流、服务端补全全部下沉 service。
 * 请求体缺必填字段经 DTO 可空建模进入 service 校验器，逐字段产出 40001 message
 * （如 "title: 必填"，round1 契约对齐）；此处仅兜底仍抛异常的解码失败
 * （非法 JSON / 字段类型不匹配），按 PRD §2.5 归 40001，不落入 50000。
 * #6 的状态/删除路由经 feedbackModerationRoutes()（独立文件）在此挂载。
 */
fun Route.feedbackRoutes() {
    feedbackModerationRoutes()
    post("/api/feedback") {
        val service: FeedbackService = call.get()
        val request = try {
            call.receive<SubmitFeedbackRequest>()
        } catch (cause: BadRequestException) {
            throw ApiException(ErrorCode.BAD_REQUEST, requestDecodeErrorMessage(cause))
        }
        val data: SubmitFeedbackResponse = service.submit(request)
        call.respond(ApiResponse(code = ErrorCode.OK, message = SUCCESS_MESSAGE, data = data))
    }
}
