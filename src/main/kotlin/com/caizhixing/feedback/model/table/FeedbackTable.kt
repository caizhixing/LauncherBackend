package com.caizhixing.feedback.model.table

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.datetime

/**
 * feedback 表定义（PRD §2.1，与仓库根 schema.sql 一一对应）：
 * 列名 snake_case、可空性、长度与 DDL 逐列对齐；
 * images 按 PRD §2.1 以 text 承载 JSON 字符串（一期恒写 "[]"，MySQL JSON 列接受字符串写入）。
 */
object FeedbackTable : Table("feedback") {
    val id = long("id").autoIncrement()
    val title = varchar("title", 50)
    val content = varchar("content", 1000)
    val category = varchar("category", 20)
    val appName = varchar("app_name", 50)
    val appVersion = varchar("app_version", 20)
    val deviceModel = varchar("device_model", 100).nullable()
    val osVersion = varchar("os_version", 20).nullable()
    val deviceId = varchar("device_id", 64)
    val contact = varchar("contact", 100).nullable()
    val status = byte("status")
    val images = text("images").nullable()
    val createdAt = datetime("created_at")
    val updatedAt = datetime("updated_at")

    override val primaryKey = PrimaryKey(id)
}
