package com.caizhixing.feedback.route

import com.caizhixing.feedback.config.AppConfig
import com.caizhixing.feedback.createServer
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
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * round1 blocker1 端到端（复用 ApplicationStartupTest 的真实 Netty 装配模式）：
 * POST /api/feedback 缺必填字段 / 非法 JSON / 字段类型错误 → 400 + code 40001，
 * message 指明具体字段与原因（PRD §2.5）。
 * 仅覆盖非法输入路径（校验先于限流与落库，不触达 DB）；合法请求依赖 MySQL，不在单测范围。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FeedbackRouteContractTest {

    private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>

    private var port: Int = 0

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
    fun missingRequiredFieldReturns40001WithFieldName() {
        val (status, body) = post(
            """{"content":"c","category":"BUG","appName":"launcher","appVersion":"1.0","deviceId":"d1"}""",
        )

        assertEquals(400, status)
        assertEquals("""{"code":40001,"message":"title: 必填","data":null}""", body)
    }

    @Test
    fun malformedJsonReturns40001() {
        val (status, body) = post("""{"title": "unterminated""")

        assertEquals(400, status)
        assertTrue(body.startsWith("""{"code":40001,"message":"请求体格式错误"""), body)
    }

    @Test
    fun typeMismatchedFieldReturns40001WithFieldHint() {
        val (status, body) = post(
            """{"title":123,"content":"c","category":"BUG","appName":"launcher",""" +
                """"appVersion":"1.0","deviceId":"d1"}""",
        )

        assertEquals(400, status)
        assertTrue(body.startsWith("""{"code":40001,"message":"请求体格式错误"""), body)
        assertTrue(body.contains("title"), "message 应含字段线索: $body")
    }

    private fun testConfig() = AppConfig(
        adminUsername = "admin",
        adminPasswordHash = "bcrypt-hash-placeholder",
        jwtSecret = "0123456789abcdef0123456789abcdef",
        dbUrl = "jdbc:mysql://127.0.0.1:3306/feedback",
        dbUser = "feedback",
        dbPassword = "secret",
        port = 0,
    )

    private fun post(body: String): Pair<Int, String> =
        HttpClient.newHttpClient().use { client ->
            val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/feedback"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(10))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            response.statusCode() to response.body()
        }
}
