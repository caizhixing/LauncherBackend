package com.caizhixing.feedback

import com.caizhixing.feedback.config.AppConfig
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 行为 1.6：真实 module 装配（createServer）在 Netty 上可启动；
 * 未匹配路由返回 Ktor 默认 404 空体（PRD §6 决策 1：不套统一响应体）。
 * 配置用直接构造的 AppConfig（port=0 取临时端口），不走环境变量。
 */
class ApplicationStartupTest {

    @Test
    fun nettyServerStartsAndUnmatchedRouteReturnsDefault404EmptyBody() {
        val config = AppConfig(
            adminUsername = "admin",
            adminPasswordHash = "bcrypt-hash-placeholder",
            jwtSecret = "0123456789abcdef0123456789abcdef",
            dbUrl = "jdbc:mysql://127.0.0.1:3306/feedback",
            dbUser = "feedback",
            dbPassword = "secret",
            port = 0,
        )
        val server = createServer(config)
        server.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            HttpClient.newHttpClient().use { client ->
                val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/unmatched-route-probe"))
                    .version(HttpClient.Version.HTTP_1_1)
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                assertEquals(404, response.statusCode())
                assertEquals("", response.body())
            }
        } finally {
            server.stop(1000, 2000)
        }
    }
}
