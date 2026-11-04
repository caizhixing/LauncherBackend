package com.caizhixing.feedback.route

import com.caizhixing.feedback.model.ApiException
import com.caizhixing.feedback.model.ErrorCode
import com.caizhixing.feedback.model.dto.ApiResponse
import com.caizhixing.feedback.plugin.AUTH_PROVIDER_NAME
import com.caizhixing.feedback.service.FeedbackService
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.koin.ktor.ext.get

private const val SUCCESS_MESSAGE = "success"

/**
 * GET /api/feedback/list（鉴权路由，PRD §2.3；issue #5 独立文件挂载，与 #6 不共区域）。
 * route 只做 Query 参数解析与响应封装：解析纯函数 FeedbackListQueryParser 产出 40001
 * message（"字段: 原因"），业务查询全部下沉 service。
 */
fun Route.feedbackListRoutes() {
    authenticate(AUTH_PROVIDER_NAME) {
        get("/api/feedback/list") {
            val service: FeedbackService = call.get()
            val query = call.request.queryParameters
            val parsed = FeedbackListQueryParser.parse(
                page = query["page"],
                pageSize = query["pageSize"],
                appName = query["appName"],
                appVersion = query["appVersion"],
                category = query["category"],
                status = query["status"],
                startDate = query["startDate"],
                endDate = query["endDate"],
                keyword = query["keyword"],
            )
            val valid = when (parsed) {
                is FeedbackListQueryResult.Invalid -> throw ApiException(ErrorCode.BAD_REQUEST, parsed.message)
                is FeedbackListQueryResult.Valid -> parsed
            }
            val data = service.list(valid.filter, valid.page, valid.pageSize)
            call.respond(ApiResponse(ErrorCode.OK, SUCCESS_MESSAGE, data))
        }
    }
}
