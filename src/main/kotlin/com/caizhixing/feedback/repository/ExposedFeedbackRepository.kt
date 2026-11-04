package com.caizhixing.feedback.repository

import com.caizhixing.feedback.model.Feedback
import com.caizhixing.feedback.model.FeedbackCategory
import com.caizhixing.feedback.model.FeedbackStatus
import com.caizhixing.feedback.model.table.FeedbackTable
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction

/**
 * Exposed 实现（PRD §2.4，round1 契约对齐）：统一 newSuspendedTransaction(Dispatchers.IO)
 * 挂起事务（Exposed v1 包），snake_case 列 ↔ camelCase 领域字段映射在本层完成；
 * images 以 JSON 字符串落库（一期恒 "[]"）。
 */
class ExposedFeedbackRepository : FeedbackRepository {

    private val imagesCodec: Json = Json

    private val imagesSerializer = ListSerializer(String.serializer())

    override suspend fun insert(feedback: Feedback): Long = newSuspendedTransaction(Dispatchers.IO) {
        FeedbackTable.insert { row ->
            row[title] = feedback.title
            row[content] = feedback.content
            row[category] = feedback.category.name
            row[appName] = feedback.appName
            row[appVersion] = feedback.appVersion
            row[deviceModel] = feedback.deviceModel
            row[osVersion] = feedback.osVersion
            row[deviceId] = feedback.deviceId
            row[contact] = feedback.contact
            row[status] = feedback.status.value.toByte()
            row[images] = imagesCodec.encodeToString(imagesSerializer, feedback.images)
            row[createdAt] = feedback.createdAt
            row[updatedAt] = feedback.updatedAt
        }[FeedbackTable.id]
    }

    override suspend fun findById(id: Long): Feedback? = newSuspendedTransaction(Dispatchers.IO) {
        FeedbackTable.selectAll()
            .where { FeedbackTable.id eq id }
            .singleOrNull()
            ?.toFeedback()
    }

    private fun ResultRow.toFeedback(): Feedback = Feedback(
        id = this[FeedbackTable.id],
        title = this[FeedbackTable.title],
        content = this[FeedbackTable.content],
        category = FeedbackCategory.entries.first { it.name == this[FeedbackTable.category] },
        appName = this[FeedbackTable.appName],
        appVersion = this[FeedbackTable.appVersion],
        deviceModel = this[FeedbackTable.deviceModel],
        osVersion = this[FeedbackTable.osVersion],
        deviceId = this[FeedbackTable.deviceId],
        contact = this[FeedbackTable.contact],
        status = statusFromDb(this[FeedbackTable.status]),
        images = this[FeedbackTable.images]?.let { imagesCodec.decodeFromString(imagesSerializer, it) } ?: emptyList(),
        createdAt = this[FeedbackTable.createdAt],
        updatedAt = this[FeedbackTable.updatedAt],
    )

    // DDL 无 CHECK 约束，脏数据落到 0/1 之外时按服务器错误冒泡（50000），不静默篡改状态
    private fun statusFromDb(value: Byte): FeedbackStatus =
        FeedbackStatus.entries.first { it.value == value.toInt() }
}
