package com.caizhixing.feedback.model

object ErrorCode {
    const val OK: Int = 0
    const val BAD_REQUEST: Int = 40001
    const val UNAUTHORIZED: Int = 40100
    const val TOKEN_EXPIRED: Int = 40101
    const val BAD_CREDENTIALS: Int = 40102
    const val NOT_FOUND: Int = 40401
    const val RATE_LIMITED: Int = 42900
    const val INTERNAL: Int = 50000
}
