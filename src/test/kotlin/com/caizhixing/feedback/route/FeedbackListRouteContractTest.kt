package com.caizhixing.feedback.route

import com.caizhixing.feedback.config.AppConfig
import com.caizhixing.feedback.createServer
import com.caizhixing.feedback.service.JwtTokenService
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
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
 * issue #5 路由装配契约（真实 Netty，复用 FeedbackRouteContractTest/AuthWiringTest 范式）：
 * GET /api/feedback/list 挂载在鉴权 provider 内（无 token → 401 + 40100）；
 * 非法 query 经 FeedbackListQueryParser → 400 + 40001（message 指明字段与原因）。
 * 合法 query 的 200 路径依赖 MySQL，不在单测范围（PRD §5 Repository n/a）；
 * 此处以降级契约替代断言：测试装配未注册 DataSource，service → repository 抛未捕获异常
 * → StatusPages 兜底 500 + 50000（DECISIONS.md #DB故障降级实现），证明路由已正确接入 service。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FeedbackListRouteContractTest {

    companion object {
        private const val SECRET = "0123456789abcdef0123456789abcdef"
    }

    private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>

    private var port: Int = 0

    private var token: String = ""

    @BeforeAll
    fun startServer() {
        server = createServer(testConfig())
        server.start(wait = false)
        port = runBlocking { server.engine.resolvedConnectors().first().port }
        token = JwtTokenService(SECRET, Clock.systemUTC()).issue("admin").token
    }

    @AfterAll
    fun stopServer() {
        server.stop(1000, 2000)
    }

    @Test
    fun listWithoutTokenReturns40100() {
        val (status, code) = get("/api/feedback/list")

        assertEquals(401, status)
        assertEquals(40100, code)
    }

    @Test
    fun listWithGarbageTokenReturns40100() {
        val (status, code) = get("/api/feedback/list", authorization = "Bearer garbage.token.value")

        assertEquals(401, status)
        assertEquals(40100, code)
    }

    @Test
    fun invalidPageReturns40001WithFieldMessage() {
        val (status, code, message) = getWithBody("/api/feedback/list?page=0", authorization = "Bearer $token")

        assertEquals(400, status)
        assertEquals(40001, code)
        assertEquals("page: 必须是不小于 1 的整数", message)
    }

    @Test
    fun invalidCategoryReturns40001WithFieldMessage() {
        val (status, code, message) = getWithBody(
            "/api/feedback/list?category=BUG,NOPE",
            authorization = "Bearer $token",
        )

        assertEquals(400, status)
        assertEquals(40001, code)
        assertTrue(message.startsWith("category:"), "message 应指明字段: $message")
    }

    @Test
    fun validQueryReachesServiceAndFailsClosedWithoutDatabase() {
        // 合法 query → 解析通过 → service.list → repository（无 DataSource）→ 未捕获异常 → 50000
        val (status, code) = get(
            "/api/feedback/list?page=2&pageSize=15&appName=launcher&category=BUG,OTHER&status=1" +
                "&startDate=2026-11-01&endDate=2026-11-30&keyword=%E9%97%AA%E9%80%80",
            authorization = "Bearer $token",
        )

        assertEquals(500, status)
        assertEquals(50000, code)
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

    private fun get(path: String, authorization: String? = null): Pair<Int, Int> {
        val (status, code, _) = getWithBody(path, authorization)
        return status to code
    }

    private fun getWithBody(path: String, authorization: String? = null): Triple<Int, Int, String> =
        HttpClient.newHttpClient().use { client ->
            val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .timeout(Duration.ofSeconds(10))
                .GET()
            authorization?.let { builder.header("Authorization", it) }
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            val body = Json.parseToJsonElement(response.body()).jsonObject
            val code = (body["code"] as? JsonPrimitive)?.int
                ?: error("response body has no code field: ${response.body()}")
            val message = (body["message"] as? JsonPrimitive)?.content
                ?: error("response body has no message field: ${response.body()}")
            Triple(response.statusCode(), code, message)
        }
}
