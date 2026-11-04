package com.caizhixing.feedback.config

import com.typesafe.config.ConfigFactory

data class AppConfig(
    val adminUsername: String,
    val adminPasswordHash: String,
    val jwtSecret: String,
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val port: Int = DEFAULT_PORT,
    val corsOrigins: List<String> = emptyList(),
) {
    companion object {
        const val DEFAULT_PORT: Int = 8080
        const val MIN_JWT_SECRET_LENGTH: Int = 32

        private val REQUIRED_KEYS = listOf(
            "ADMIN_USERNAME",
            "ADMIN_PASSWORD_HASH",
            "JWT_SECRET",
            "DB_URL",
            "DB_USER",
            "DB_PASSWORD",
        )

        fun load(
            env: (String) -> String? = System::getenv,
            conf: Map<String, String> = confDefaults(),
        ): AppConfig {
            fun value(key: String): String? =
                env(key).takeUnless { it.isNullOrBlank() } ?: conf[key].takeUnless { it.isNullOrBlank() }

            val missing = REQUIRED_KEYS.filter { value(it) == null }
            require(missing.isEmpty()) { "Missing required configuration variables: ${missing.joinToString(", ")}" }

            val jwtSecret = value("JWT_SECRET").orEmpty()
            require(jwtSecret.length >= MIN_JWT_SECRET_LENGTH) {
                "Invalid configuration variable JWT_SECRET: must be at least $MIN_JWT_SECRET_LENGTH characters, got ${jwtSecret.length}"
            }

            val portRaw = value("PORT")
            val port = if (portRaw == null) {
                DEFAULT_PORT
            } else {
                requireNotNull(portRaw.toIntOrNull()) {
                    "Invalid configuration variable PORT: expected an integer, got '$portRaw'"
                }
            }

            val corsOrigins = value("CORS_ORIGINS")
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()

            return AppConfig(
                adminUsername = value("ADMIN_USERNAME").orEmpty(),
                adminPasswordHash = value("ADMIN_PASSWORD_HASH").orEmpty(),
                jwtSecret = jwtSecret,
                dbUrl = value("DB_URL").orEmpty(),
                dbUser = value("DB_USER").orEmpty(),
                dbPassword = value("DB_PASSWORD").orEmpty(),
                port = port,
                corsOrigins = corsOrigins,
            )
        }

        internal fun confDefaults(): Map<String, String> {
            val root = ConfigFactory.load()
            if (!root.hasPath("feedback")) return emptyMap()
            val feedback = root.getConfig("feedback")
            return buildMap {
                if (feedback.hasPath("port")) put("PORT", feedback.getInt("port").toString())
                if (feedback.hasPath("corsOrigins")) {
                    val origins = feedback.getStringList("corsOrigins")
                    if (origins.isNotEmpty()) put("CORS_ORIGINS", origins.joinToString(","))
                }
            }
        }
    }
}
