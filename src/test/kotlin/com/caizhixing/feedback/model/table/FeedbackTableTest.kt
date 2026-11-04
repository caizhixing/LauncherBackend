package com.caizhixing.feedback.model.table

import org.jetbrains.exposed.v1.core.AutoIncColumnType
import org.jetbrains.exposed.v1.core.VarCharColumnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 行为 4.6：FeedbackTable 列名/可空性/长度/类型与 schema.sql 一一对应（常量断言）。
 */
class FeedbackTableTest {

    @Test
    fun tableNameMatchesSchema() {
        assertEquals("feedback", FeedbackTable.tableName)
    }

    @Test
    fun columnNamesMatchSchemaSnakeCaseInOrder() {
        val names = FeedbackTable.columns.map { it.name }
        assertEquals(
            listOf(
                "id", "title", "content", "category", "app_name", "app_version",
                "device_model", "os_version", "device_id", "contact", "status",
                "images", "created_at", "updated_at",
            ),
            names,
        )
    }

    @Test
    fun nullabilityMatchesSchema() {
        val nullableNames = setOf("device_model", "os_version", "contact", "images")
        FeedbackTable.columns.forEach { column ->
            assertEquals(nullableNames.contains(column.name), column.columnType.nullable, "column ${column.name}")
        }
    }

    @Test
    fun varcharLengthsMatchSchema() {
        val expected = mapOf(
            "title" to 50,
            "content" to 1000,
            "category" to 20,
            "app_name" to 50,
            "app_version" to 20,
            "device_model" to 100,
            "os_version" to 20,
            "device_id" to 64,
            "contact" to 100,
        )
        expected.forEach { (name, length) ->
            val column = FeedbackTable.columns.first { it.name == name }
            val type = assertIs<VarCharColumnType>(column.columnType, "column $name 应为 VARCHAR")
            assertEquals(length, type.colLength, "column $name 长度")
        }
    }

    @Test
    fun idStatusImagesAndTimeColumnsMatchSchema() {
        val idType = assertIs<AutoIncColumnType<*>>(FeedbackTable.id.columnType)
        assertEquals("LongColumnType", idType.delegate::class.simpleName) // BIGINT AUTO_INCREMENT
        assertEquals(listOf(FeedbackTable.id), FeedbackTable.primaryKey.columns.toList())

        assertEquals("ByteColumnType", columnTypeOf("status")::class.simpleName) // TINYINT
        assertEquals("TextColumnType", columnTypeOf("images")::class.simpleName) // JSON 以 text 写入
        assertTrue(
            columnTypeOf("created_at")::class.simpleName.orEmpty().contains("LocalDateTime"),
            "created_at 应为 datetime 列",
        )
        assertTrue(
            columnTypeOf("updated_at")::class.simpleName.orEmpty().contains("LocalDateTime"),
            "updated_at 应为 datetime 列",
        )
    }

    private fun columnTypeOf(name: String) = FeedbackTable.columns.first { it.name == name }.columnType
}
