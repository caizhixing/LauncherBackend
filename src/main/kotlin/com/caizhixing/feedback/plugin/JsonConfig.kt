package com.caizhixing.feedback.plugin

import kotlinx.serialization.json.Json

object JsonConfig {
    val json: Json = Json {
        explicitNulls = false
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
}
