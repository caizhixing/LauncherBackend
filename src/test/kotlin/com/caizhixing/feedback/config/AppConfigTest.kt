package com.caizhixing.feedback.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AppConfigTest {

    private val requiredValues = mapOf(
        "ADMIN_USERNAME" to "admin",
        "ADMIN_PASSWORD_HASH" to "bcrypt-hash-placeholder",
        "JWT_SECRET" to "0123456789abcdef0123456789abcdef",
        "DB_URL" to "jdbc:mysql://127.0.0.1:3306/feedback",
        "DB_USER" to "feedback",
        "DB_PASSWORD" to "secret",
    )

    private fun envWith(overrides: Map<String, String> = emptyMap()): (String) -> String? =
        { key -> (requiredValues + overrides)[key] }

    @Test
    fun missingRequiredVariablesFailFastWithFullList() {
        val env: (String) -> String? =
            { key -> (requiredValues - setOf("JWT_SECRET", "DB_URL", "DB_PASSWORD"))[key] }
        val error = assertFailsWith<IllegalArgumentException> {
            AppConfig.load(env = env, conf = emptyMap())
        }
        val message = error.message ?: ""
        assertTrue(message.contains("JWT_SECRET"), "message should list JWT_SECRET but was: $message")
        assertTrue(message.contains("DB_URL"), "message should list DB_URL but was: $message")
        assertTrue(message.contains("DB_PASSWORD"), "message should list DB_PASSWORD but was: $message")
    }

    @Test
    fun shortJwtSecretRejected() {
        val env = envWith(mapOf("JWT_SECRET" to "short-secret"))
        val error = assertFailsWith<IllegalArgumentException> {
            AppConfig.load(env = env, conf = emptyMap())
        }
        val message = error.message ?: ""
        assertTrue(message.contains("JWT_SECRET"), "message should name JWT_SECRET but was: $message")
    }

    @Test
    fun jwtSecretOfExactly31CharactersRejected() {
        val secret31 = "0123456789abcdef0123456789abcde"
        assertEquals(31, secret31.length)
        val env = envWith(mapOf("JWT_SECRET" to secret31))
        val error = assertFailsWith<IllegalArgumentException> {
            AppConfig.load(env = env, conf = emptyMap())
        }
        val message = error.message ?: ""
        assertTrue(message.contains("JWT_SECRET"), "message should name JWT_SECRET but was: $message")
        assertTrue(message.contains("got 31"), "message should report actual length 31 but was: $message")
    }

    @Test
    fun jwtSecretOfExactly32CharactersAccepted() {
        val secret32 = "0123456789abcdef0123456789abcdef"
        assertEquals(32, secret32.length)
        val config = AppConfig.load(env = envWith(mapOf("JWT_SECRET" to secret32)), conf = emptyMap())
        assertEquals(secret32, config.jwtSecret)
    }

    @Test
    fun defaultLoadDelegateReadsProcessEnvironmentAndFailsFast() {
        // 无参 load() 走默认参数 System::getenv（生产同路径；测试 JVM 不注入必填业务变量）：
        // 覆盖该默认参数分支并断言 fail fast 输出真实进程环境的缺失清单
        val expectedMissing = listOf(
            "ADMIN_USERNAME",
            "ADMIN_PASSWORD_HASH",
            "JWT_SECRET",
            "DB_URL",
            "DB_USER",
            "DB_PASSWORD",
        ).filter { System.getenv(it).isNullOrBlank() }
        assertTrue(expectedMissing.isNotEmpty(), "test JVM must not define the required business variables")
        val error = assertFailsWith<IllegalArgumentException> { AppConfig.load() }
        val message = error.message ?: ""
        expectedMissing.forEach { key ->
            assertTrue(message.contains(key), "message should list $key but was: $message")
        }
    }

    @Test
    fun defaultsApplied() {
        val config = AppConfig.load(env = envWith(), conf = emptyMap())
        assertEquals(AppConfig.DEFAULT_PORT, config.port)
        assertEquals(emptyList(), config.corsOrigins)
        assertEquals("admin", config.adminUsername)
        assertEquals("jdbc:mysql://127.0.0.1:3306/feedback", config.dbUrl)
    }

    @Test
    fun corsOriginsParsedFromCommaSeparatedEnv() {
        val env = envWith(mapOf("CORS_ORIGINS" to "https://admin.example.com, https://app.example.com ,,"))
        val config = AppConfig.load(env = env, conf = emptyMap())
        assertEquals(listOf("https://admin.example.com", "https://app.example.com"), config.corsOrigins)
    }

    @Test
    fun envTakesPrecedenceOverApplicationConf() {
        val env = envWith(mapOf("PORT" to "9090"))
        val config = AppConfig.load(env = env, conf = mapOf("PORT" to "8080"))
        assertEquals(9090, config.port)
    }

    @Test
    fun invalidPortFailsFast() {
        val env = envWith(mapOf("PORT" to "not-a-number"))
        val error = assertFailsWith<IllegalArgumentException> {
            AppConfig.load(env = env, conf = emptyMap())
        }
        val message = error.message ?: ""
        assertTrue(message.contains("PORT"), "message should name PORT but was: $message")
    }

    @Test
    fun blankRequiredValueCountsAsMissing() {
        val env: (String) -> String? = { key -> (requiredValues + ("DB_USER" to "   "))[key] }
        val error = assertFailsWith<IllegalArgumentException> {
            AppConfig.load(env = env, conf = emptyMap())
        }
        val message = error.message ?: ""
        assertTrue(message.contains("DB_USER"), "message should list DB_USER but was: $message")
    }

    @Test
    fun applicationConfProvidesFallbackDefaults() {
        val defaults = AppConfig.confDefaults()
        assertEquals("8080", defaults["PORT"])
    }

    @Test
    fun dataClassDefaultsMatchFrozenContract() {
        val config = AppConfig(
            adminUsername = "admin",
            adminPasswordHash = "hash",
            jwtSecret = "0123456789abcdef0123456789abcdef",
            dbUrl = "jdbc:mysql://127.0.0.1:3306/feedback",
            dbUser = "feedback",
            dbPassword = "secret",
        )
        assertEquals(8080, config.port)
        assertEquals(emptyList(), config.corsOrigins)
    }

    @Test
    fun loadFallsBackToApplicationConfWhenEnvAbsent() {
        // conf 参数走默认 confDefaults()，实读 classpath 中的 application.conf：PORT 未设 → conf 的 8080
        val config = AppConfig.load(env = envWith())
        assertEquals(8080, config.port)
        assertEquals(emptyList(), config.corsOrigins)
    }
}
