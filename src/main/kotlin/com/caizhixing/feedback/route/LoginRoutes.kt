package com.caizhixing.feedback.route

import com.caizhixing.feedback.model.ApiException
import com.caizhixing.feedback.model.ErrorCode
import com.caizhixing.feedback.model.dto.ApiResponse
import com.caizhixing.feedback.model.dto.LoginRequest
import com.caizhixing.feedback.service.AuthService
import com.caizhixing.feedback.util.ClientIpResolver
import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.koin.ktor.ext.inject
import java.util.concurrent.CancellationException

fun Route.loginRoutes() {
    post("/api/login") {
        val authService by call.inject<AuthService>()
        // 请求体解析失败（JSON 畸形/字段类型错）统一按参数错误回 40001，避免落入兜底 50000；
        // 协程取消异常必须原样上抛，否则会吞掉请求中断信号
        val request = try {
            call.receive<LoginRequest>()
        } catch (cause: Exception) {
            if (cause is CancellationException) throw cause
            throw ApiException(ErrorCode.BAD_REQUEST, "登录请求体格式错误，username 与 password 为必填字符串字段")
        }
        val clientIp = ClientIpResolver.resolve(
            xForwardedFor = call.request.headers[HttpHeaders.XForwardedFor],
            remoteHost = call.request.origin.remoteHost,
        )
        val response = authService.login(request, clientIp)
        call.respond(ApiResponse(ErrorCode.OK, "success", response))
    }
}
