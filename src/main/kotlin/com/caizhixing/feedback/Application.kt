package com.caizhixing.feedback

import com.caizhixing.feedback.config.AppConfig
import com.caizhixing.feedback.config.DatabaseConnect
import com.caizhixing.feedback.plugin.installHttpPlugins
import com.caizhixing.feedback.util.AppTime
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import java.time.Clock

// 真实 module 装配独立成函数：main 保持薄入口，行为 1.6 启动断言测试直接复用此装配
fun createServer(config: AppConfig): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> =
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        install(Koin) {
            modules(
                module {
                    single<AppConfig> { config }
                    single<Clock> { Clock.system(AppTime.ZONE) }
                },
            )
        }
        installHttpPlugins(config)
    }

fun main() {
    val config = AppConfig.load()
    DatabaseConnect.connect(config)
    createServer(config).start(wait = true)
}
