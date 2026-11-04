package com.caizhixing.feedback.plugin

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.caizhixing.feedback.config.AppConfig
import com.caizhixing.feedback.model.ErrorCode
import com.caizhixing.feedback.model.dto.ApiResponse
import com.caizhixing.feedback.service.JwtTokenService
import com.caizhixing.feedback.service.TokenVerification
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.response.respond
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

const val AUTH_PROVIDER_NAME: String = "auth-jwt"

private const val BEARER_PREFIX = "Bearer "

fun Application.installAuthWiring(config: AppConfig, tokenService: JwtTokenService) {
    install(Authentication) {
        jwt(AUTH_PROVIDER_NAME) {
            realm = "feedback-backend"
            verifier(JWT.require(Algorithm.HMAC256(config.jwtSecret)).build())
            validate { credential ->
                val username = credential.payload.subject
                if (username.isNullOrBlank()) null else UserIdPrincipal(username)
            }
            challenge { _, _ ->
                // ktor 挑战回调不携带失败原因，复验一次以区分 40100（缺失/无效）与 40101（过期）
                val token = call.request.headers[HttpHeaders.Authorization]
                    ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
                    ?.substring(BEARER_PREFIX.length)
                    ?.trim()
                when (val verification = token?.let { tokenService.verify(it) }) {
                    null -> call.respond(
                        HttpStatusCode.Unauthorized,
                        unauthorizedBody(ErrorCode.UNAUTHORIZED, "未登录或 Token 缺失"),
                    )
                    TokenVerification.Expired -> call.respond(
                        HttpStatusCode.Unauthorized,
                        unauthorizedBody(ErrorCode.TOKEN_EXPIRED, "Token 已过期"),
                    )
                    else -> call.respond(
                        HttpStatusCode.Unauthorized,
                        unauthorizedBody(ErrorCode.UNAUTHORIZED, "Token 无效或格式错误"),
                    )
                }
            }
        }
    }
}

private fun unauthorizedBody(code: Int, message: String): ApiResponse<JsonElement> =
    ApiResponse(code = code, message = message, data = JsonNull)
