package com.caizhixing.feedback.plugin

import com.caizhixing.feedback.config.AppConfig
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.serialization.kotlinx.json.json

fun Application.installHttpPlugins(config: AppConfig) {
    install(ContentNegotiation) {
        json(JsonConfig.json)
    }
    install(CORS) {
        // CORS_ORIGINS 条目两种格式都接受：含 scheme 的完整 origin 精确匹配，裸域名按 host 放行
        config.corsOrigins.forEach { entry ->
            if (entry.contains("://")) {
                allowOrigins { it == entry }
            } else {
                allowHost(entry)
            }
        }
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.ContentType)
    }
    install(CallLogging)
    installErrorMapping()
}
