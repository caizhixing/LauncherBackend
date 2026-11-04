package com.caizhixing.feedback

import com.caizhixing.feedback.config.AppConfig
import com.caizhixing.feedback.config.DatabaseConnect
import com.caizhixing.feedback.plugin.installAuthWiring
import com.caizhixing.feedback.plugin.installHttpPlugins
import com.caizhixing.feedback.route.loginRoutes
import com.caizhixing.feedback.service.AuthService
import com.caizhixing.feedback.service.JwtTokenService
import com.caizhixing.feedback.util.AppTime
import com.caizhixing.feedback.util.FixedWindowRateLimiter
import com.caizhixing.feedback.util.RateLimiter
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.routing.Routing
import io.ktor.server.routing.routing
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import java.time.Clock

// 真实 module 装配独立成函数：main 保持薄入口，行为 1.6 启动断言测试直接复用此装配；
// extraRoutes 供鉴权装配测试挂临时受保护探针路由，生产装配不传
fun Application.feedbackServerModule(config: AppConfig, extraRoutes: Routing.() -> Unit = {}) {
    val clock = Clock.system(AppTime.ZONE)
    val tokenService = JwtTokenService(config.jwtSecret, clock)
    val loginRateLimiter: RateLimiter = FixedWindowRateLimiter(
        minuteLimit = AuthService.LOGIN_RATE_LIMIT_PER_MINUTE,
        dayLimit = null,
        clock = clock,
    )
    val authService = AuthService(config.adminUsername, config.adminPasswordHash, tokenService, loginRateLimiter)

    install(Koin) {
        modules(
            module {
                single<AppConfig> { config }
                single<Clock> { clock }
                single { tokenService }
                single<RateLimiter>(named("loginRateLimiter")) { loginRateLimiter }
                single { authService }
            },
        )
    }
    installHttpPlugins(config)
    installAuthWiring(config, tokenService)
    routing {
        loginRoutes()
        extraRoutes()
    }
}

fun createServer(config: AppConfig): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> =
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        feedbackServerModule(config)
    }

fun main() {
    val config = AppConfig.load()
    DatabaseConnect.connect(config)
    createServer(config).start(wait = true)
}
