package com.caizhixing.feedback

import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * 引导期依赖冒烟测试：强制 Gradle 解析并缓存全部运行时依赖（一次性联网授权内），
 * 并防止后续任何依赖被误删。issue #1 落地骨架后可保留或并入启动测试。
 * 探针类名均按 Ktor 3.6 / Exposed 1.6 实际字节码核对。
 */
class DependencySmokeTest {

    @Test
    fun runtimeDependenciesAreResolvable() {
        listOf(
            "io.ktor.server.netty.Netty",
            "io.ktor.server.application.Application",
            "io.ktor.serialization.kotlinx.json.KotlinxSerializationJsonExtensionProvider",
            "kotlinx.serialization.json.Json",
            "io.ktor.server.plugins.statuspages.StatusPagesConfig",
            "io.ktor.server.plugins.calllogging.CallLoggingConfig",
            "io.ktor.server.plugins.cors.CORSConfig",
            "io.ktor.server.auth.Authentication",
            "io.ktor.server.auth.jwt.JWTPrincipal",
            "org.jetbrains.exposed.v1.jdbc.Database",
            "org.jetbrains.exposed.v1.javatime.JavaLocalDateTimeColumnType",
            "org.koin.ktor.plugin.RequestScope",
            "com.zaxxer.hikari.HikariDataSource",
            "at.favre.lib.crypto.bcrypt.BCrypt",
            "ch.qos.logback.classic.LoggerContext",
            "com.mysql.cj.jdbc.Driver",
        ).forEach { className ->
            assertNotNull(Class.forName(className), className)
        }
    }
}
