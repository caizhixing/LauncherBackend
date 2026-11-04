package com.caizhixing.feedback.plugin

import at.favre.lib.crypto.bcrypt.BCrypt
import com.caizhixing.feedback.config.AppConfig
import com.caizhixing.feedback.feedbackServerModule
import com.caizhixing.feedback.model.ErrorCode
import com.caizhixing.feedback.model.dto.ApiResponse
import com.caizhixing.feedback.service.AuthService
import com.caizhixing.feedback.service.JwtTokenService
import com.caizhixing.feedback.util.MutableClock
import com.caizhixing.feedback.util.RateLimiter
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.koin.core.qualifier.named
import org.koin.ktor.ext.get
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 行为 3.6：鉴权装配整体验证。复用生产装配 feedbackServerModule，仅追加临时受保护探针路由；
 * 用真实 Netty + java.net.http 客户端（测试栈不引 ktor-server-test-host）。
 * 过期 token 通过给 JwtTokenService 注入落后 7 天+120 秒的时钟铸造，判定仍以真实系统时钟为准。
 */
class AuthWiringTest {

    companion object {
        private const val SECRET = "0123456789abcdef0123456789abcdef"
        private const val USERNAME = "admin"
        private const val PASSWORD = "wiring-test-password"
        private val PASSWORD_HASH: String by lazy { BCrypt.withDefaults().hashToString(10, PASSWORD.toCharArray()) }

        private const val PROBE_PATH = "/api/auth-probe"

        private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
        private lateinit var client: HttpClient
        private var port: Int = 0

        // 装配断言用：extraRoutes 在模块安装期执行，把 Koin 解析结果带出闭包供测试核对
        private lateinit var resolvedAppConfig: AppConfig
        private lateinit var resolvedClock: Clock
        private lateinit var resolvedTokenService: JwtTokenService
        private lateinit var resolvedLoginRateLimiter: RateLimiter
        private lateinit var resolvedAuthService: AuthService

        @BeforeAll
        @JvmStatic
        fun startServer() {
            val config = AppConfig(
                adminUsername = USERNAME,
                adminPasswordHash = PASSWORD_HASH,
                jwtSecret = SECRET,
                dbUrl = "jdbc:mysql://127.0.0.1:3306/feedback",
                dbUser = "feedback",
                dbPassword = "unused-in-this-test",
                port = 0,
            )
            server = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
                feedbackServerModule(config) {
                    resolvedAppConfig = get<AppConfig>()
                    resolvedClock = get()
                    resolvedTokenService = get<JwtTokenService>()
                    resolvedLoginRateLimiter = get<RateLimiter>(named("loginRateLimiter"))
                    resolvedAuthService = get<AuthService>()
                    authenticate(AUTH_PROVIDER_NAME) {
                        get(PROBE_PATH) {
                            val body: ApiResponse<JsonElement> = ApiResponse(ErrorCode.OK, "success", JsonNull)
                            call.respond(body)
                        }
                    }
                }
            }.start(wait = false)
            port = runBlocking { server.engine.resolvedConnectors().first().port }
            client = HttpClient.newHttpClient()
        }

        @AfterAll
        @JvmStatic
        fun stopServer() {
            client.close()
            server.stop(1000, 2000)
        }
    }

    private fun get(path: String, authorization: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .version(HttpClient.Version.HTTP_1_1)
            .timeout(Duration.ofSeconds(10))
            .GET()
        authorization?.let { builder.header("Authorization", it) }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun postLogin(username: String, password: String): HttpResponse<String> =
        postLoginRaw("""{"username":"$username","password":"$password"}""")

    private fun postLoginRaw(body: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/login"))
            .version(HttpClient.Version.HTTP_1_1)
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun codeOf(response: HttpResponse<String>): Int =
        (Json.parseToJsonElement(response.body()).jsonObject["code"] as? JsonPrimitive)?.int
            ?: error("response body has no code field: ${response.body()}")

    private fun messageOf(response: HttpResponse<String>): String =
        (Json.parseToJsonElement(response.body()).jsonObject["message"] as? JsonPrimitive)?.content
            ?: error("response body has no message field: ${response.body()}")

    private fun dataOf(response: HttpResponse<String>) =
        Json.parseToJsonElement(response.body()).jsonObject["data"]?.jsonObject
            ?: error("response body has no data object: ${response.body()}")

    @Test
    fun protectedRouteWithoutTokenReturns40100() {
        val response = get(PROBE_PATH)

        assertEquals(401, response.statusCode())
        assertEquals(ErrorCode.UNAUTHORIZED, codeOf(response))
    }

    @Test
    fun protectedRouteWithGarbageTokenReturns40100() {
        val response = get(PROBE_PATH, authorization = "Bearer garbage.token.value")

        assertEquals(401, response.statusCode())
        assertEquals(ErrorCode.UNAUTHORIZED, codeOf(response))
    }

    @Test
    fun protectedRouteWithNonBearerSchemeReturns40100() {
        val response = get(PROBE_PATH, authorization = "Basic dXNlcjpwYXNz")

        assertEquals(401, response.statusCode())
        assertEquals(ErrorCode.UNAUTHORIZED, codeOf(response))
    }

    @Test
    fun protectedRouteWithExpiredTokenReturns40101() {
        val expiredClock = MutableClock(Instant.now().minusSeconds(JwtTokenService.EXPIRES_IN_SECONDS + 120))
        val expiredToken = JwtTokenService(SECRET, expiredClock).issue(USERNAME).token

        val response = get(PROBE_PATH, authorization = "Bearer $expiredToken")

        assertEquals(401, response.statusCode())
        assertEquals(ErrorCode.TOKEN_EXPIRED, codeOf(response))
    }

    @Test
    fun protectedRouteWithValidTokenPasses() {
        val token = JwtTokenService(SECRET, Clock.systemUTC()).issue(USERNAME).token

        val response = get(PROBE_PATH, authorization = "Bearer $token")

        assertEquals(200, response.statusCode())
        assertEquals(ErrorCode.OK, codeOf(response))
    }

    @Test
    fun loginRouteIsPublicAndRejectsBadCredentials() {
        val response = postLogin(username = USERNAME, password = "wrong-password")

        assertEquals(401, response.statusCode())
        assertEquals(ErrorCode.BAD_CREDENTIALS, codeOf(response))
    }

    @Test
    fun loginRouteWithMalformedJsonBodyReturns40001() {
        // 行为 3.2 前半：JSON 语法畸形 → 路由内反序列化失败分支统一 40001，不落入兜底 50000
        val response = postLoginRaw("""{"username":"admin","password":""")

        assertEquals(400, response.statusCode())
        assertEquals(ErrorCode.BAD_REQUEST, codeOf(response))
        assertTrue(messageOf(response).contains("登录请求体格式错误"))
    }

    @Test
    fun loginRouteWithFieldTypeMismatchReturns40001() {
        // 行为 3.2 后半：字段类型错（username 传数字）同样落在反序列化失败分支
        val response = postLoginRaw("""{"username":123,"password":"x"}""")

        assertEquals(400, response.statusCode())
        assertEquals(ErrorCode.BAD_REQUEST, codeOf(response))
        assertTrue(messageOf(response).contains("登录请求体格式错误"))
    }

    @Test
    fun koinModuleExposesAllRequiredSingles() {
        // 装配断言：登录链路依赖的 5 个单例均可从 Koin 解析（限流器以命名限定，#4 注册自己的时不冲突）
        assertEquals(USERNAME, resolvedAppConfig.adminUsername)
        assertIs<JwtTokenService>(resolvedTokenService)
        assertIs<AuthService>(resolvedAuthService)
        assertIs<Clock>(resolvedClock)
        assertIs<RateLimiter>(resolvedLoginRateLimiter)
    }

    @Test
    fun loginFullFlowIssuesToken() {
        val response = postLogin(username = USERNAME, password = PASSWORD)

        assertEquals(200, response.statusCode())
        assertEquals(ErrorCode.OK, codeOf(response))
        val data = dataOf(response)
        assertTrue((data["token"] as? JsonPrimitive)?.content?.isNotBlank() == true)
        assertEquals(604800L, (data["expiresIn"] as? JsonPrimitive)?.long)
    }
}
