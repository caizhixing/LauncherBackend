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
