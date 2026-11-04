package com.caizhixing.feedback.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.jdbc.Database

object DatabaseConnect {

    fun connect(config: AppConfig): Database {
        val hikari = HikariConfig().apply {
            jdbcUrl = config.dbUrl
            username = config.dbUser
            password = config.dbPassword
            // 池启动不试连：DB 不可用不阻断启动，借出连接时才暴露失败（DECISIONS.md #DB故障降级实现）
            initializationFailTimeout = -1
        }
        return Database.connect(HikariDataSource(hikari))
    }
}
