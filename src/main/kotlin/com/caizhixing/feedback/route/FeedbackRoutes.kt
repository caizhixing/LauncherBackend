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
 * 校验、限流、服务端补全全部下沉 service。请求体无法反序列化为 SubmitFeedbackRequest
 * （非法 JSON / 缺必填字段）按契约归 40001，不落入 50000。
 */
fun Route.feedbackRoutes() {
    post("/api/feedback") {
        val service: FeedbackService = call.get()
        val request = try {
            call.receive<SubmitFeedbackRequest>()
        } catch (cause: BadRequestException) {
            throw ApiException(ErrorCode.BAD_REQUEST, "请求体格式错误：非法 JSON 或缺少必填字段")
        }
        val data: SubmitFeedbackResponse = service.submit(request)
        call.respond(ApiResponse(code = ErrorCode.OK, message = SUCCESS_MESSAGE, data = data))
    }
}
