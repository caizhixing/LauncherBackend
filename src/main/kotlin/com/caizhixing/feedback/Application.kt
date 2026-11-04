package com.caizhixing.feedback

import com.caizhixing.feedback.config.AppConfig
import com.caizhixing.feedback.config.DatabaseConnect
import com.caizhixing.feedback.plugin.installAuthWiring
import com.caizhixing.feedback.plugin.installHttpPlugins
import com.caizhixing.feedback.repository.ExposedFeedbackRepository
import com.caizhixing.feedback.repository.FeedbackRepository
import com.caizhixing.feedback.route.feedbackRoutes
import com.caizhixing.feedback.route.loginRoutes
import com.caizhixing.feedback.service.AuthService
import com.caizhixing.feedback.service.FeedbackService
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

// 提交限流阈值（PRD §2.5）：同 deviceId 1 次/分钟 + 5 次/天；与 #3 登录限流器按名隔离
private val FEEDBACK_RATE_LIMITER = named("feedbackRateLimiter")

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
                single<FeedbackRepository> { ExposedFeedbackRepository() }
                // 单实例同时携带分钟/天两个固定窗口（DECISIONS.md #限流语义，复用 #2 RateLimiter）。
                // 注册类型必须声明为 RateLimiter 接口：Koin 按声明类型索引限定绑定，
                // 用实现类型注册会让 FeedbackService 的 get(RateLimiter, 限定符) 解析失败 → 全量 50000
                single<RateLimiter>(FEEDBACK_RATE_LIMITER) {
                    FixedWindowRateLimiter(minuteLimit = 1, dayLimit = 5, clock = get())
                }
                single {
                    FeedbackService(
                        repository = get(),
                        rateLimiter = get(FEEDBACK_RATE_LIMITER),
                        clock = get(),
                    )
                }
            },
        )
    }
    installHttpPlugins(config)
    installAuthWiring(config, tokenService)
    routing {
        loginRoutes()
        feedbackRoutes()
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
