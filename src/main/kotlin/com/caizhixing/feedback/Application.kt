package com.caizhixing.feedback

import com.caizhixing.feedback.config.AppConfig
import com.caizhixing.feedback.config.DatabaseConnect
import com.caizhixing.feedback.plugin.installHttpPlugins
import com.caizhixing.feedback.util.AppTime
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import java.time.Clock

fun main() {
    val config = AppConfig.load()
    DatabaseConnect.connect(config)
    val server = embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
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
    server.start(wait = true)
}
