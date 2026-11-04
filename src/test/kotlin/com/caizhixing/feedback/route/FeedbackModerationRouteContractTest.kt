package com.caizhixing.feedback.route

import com.caizhixing.feedback.config.AppConfig
import com.caizhixing.feedback.createServer
import com.caizhixing.feedback.service.JwtTokenService
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 行为 6.1/6.4 端到端（复用 FeedbackRouteContractTest 的真实 Netty 装配模式）：
 * 鉴权门（无 token 401 而非 Ktor 默认 404 空体，证明路由已挂载且包在 authenticate 内）
 * 与不触达 DB 的 40001 路径（id 非数字 / status 非法或缺字段 / 解码失败均在校验期短路）。
 * 40401 与成功路径依赖 MySQL，不在单测范围（同 #4 口径）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FeedbackModerationRouteContractTest {

    private companion object {
        const val SECRET: String = "0123456789abcdef0123456789abcdef"
    }

    private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>

    private var port: Int = 0

    private val token: String by lazy { JwtTokenService(SECRET, Clock.systemUTC()).issue("admin").token }

    @BeforeAll
    fun startServer() {
        server = createServer(testConfig())
        server.start(wait = false)
        port = runBlocking { server.engine.resolvedConnectors().first().port }
    }

    @AfterAll
    fun stopServer() {
        server.stop(1000, 2000)
    }

    @Test
    fun statusAndDeleteRoutesAreAuthGated() {
        assertEquals(401, put("/api/feedback/1/status", """{"status":1}""").statusCode())
        assertEquals(401, delete("/api/feedback/1").statusCode())
    }

    @Test
    fun nonNumericIdWithTokenReturns40001() {
        val update = put("/api/feedback/abc/status", """{"status":1}""", token)

        assertEquals(400, update.statusCode())
        assertEquals("""{"code":40001,"message":"id: 必须为数字","data":null}""", update.body())

        val remove = delete("/api/feedback/1x", token)

        assertEquals(400, remove.statusCode())
        assertEquals("""{"code":40001,"message":"id: 必须为数字","data":null}""", remove.body())
    }

    @Test
    fun invalidStatusValueWithTokenReturns40001() {
        val response = put("/api/feedback/1/status", """{"status":2}""", token)

        assertEquals(400, response.statusCode())
        assertEquals("""{"code":40001,"message":"status: 仅允许 0 或 1","data":null}""", response.body())
    }

    @Test
    fun missingStatusFieldReturns40001() {
        val response = put("/api/feedback/1/status", """{}""", token)

        assertEquals(400, response.statusCode())
        assertEquals("""{"code":40001,"message":"status: 必填","data":null}""", response.body())
    }

    @Test
    fun malformedBodyReturns40001() {
        val response = put("/api/feedback/1/status", """{"status": "unterminated""", token)

        assertEquals(400, response.statusCode())
        assertTrue(response.body().startsWith("""{"code":40001,"message":"请求体格式错误"""), response.body())
    }

    @Test
    fun typeMismatchedStatusReturns40001() {
        // status 传字符串（非数字）：反序列化失败分支，不落入 50000
        val response = put("/api/feedback/1/status", """{"status":"done"}""", token)

        assertEquals(400, response.statusCode())
        assertTrue(response.body().startsWith("""{"code":40001,"message":"请求体格式错误"""), response.body())
        assertTrue(response.body().contains("status"), "message 应含字段线索: ${response.body()}")
    }

    private fun testConfig() = AppConfig(
        adminUsername = "admin",
        adminPasswordHash = "bcrypt-hash-placeholder",
        jwtSecret = SECRET,
        dbUrl = "jdbc:mysql://127.0.0.1:3306/feedback",
        dbUser = "feedback",
        dbPassword = "secret",
        port = 0,
    )

    private fun put(path: String, body: String, token: String? = null): HttpResponse<String> =
        exchange(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)),
            token,
        )

    private fun delete(path: String, token: String? = null): HttpResponse<String> =
        exchange(HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).DELETE(), token)

    private fun exchange(builder: HttpRequest.Builder, token: String?): HttpResponse<String> {
        token?.let { builder.header("Authorization", "Bearer $it") }
        return HttpClient.newHttpClient().use { client ->
            client.send(builder.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString())
        }
    }
}
