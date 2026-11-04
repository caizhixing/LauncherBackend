package com.caizhixing.feedback.util

object ClientIpResolver {

    // Nginx 终结 HTTPS 后 remoteHost 恒为反代地址，XFF 首个才是真实客户端（DECISIONS.md #登录限流取IP方式）
    fun resolve(xForwardedFor: String?, remoteHost: String): String =
        xForwardedFor?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: remoteHost
}
