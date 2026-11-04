package com.caizhixing.feedback.repository

import com.caizhixing.feedback.model.Feedback
import com.caizhixing.feedback.model.FeedbackCategory
import com.caizhixing.feedback.model.FeedbackListFilter
import com.caizhixing.feedback.model.FeedbackStatus
import com.caizhixing.feedback.model.table.FeedbackTable
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.LocalDateTime

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

    override suspend fun findTotalAndList(
        filter: FeedbackListFilter,
        page: Int,
        pageSize: Int,
    ): Pair<Long, List<Feedback>> = newSuspendedTransaction(Dispatchers.IO) {
        val condition = listFilterCondition(filter)
        // Query 不可变（where/limit/offset 均返回新实例）：total 与分页列表共用同一筛选
        val filtered = condition?.let { FeedbackTable.selectAll().where { condition } }
            ?: FeedbackTable.selectAll()
        val total = filtered.count()
        // 排序固定 createdAt DESC（PRD §2.5，issue #5 行为 5.8：无任何排序参数，不可配置）；
        // page 超界时 offset 越界自然返回空行集，total 不受分页影响
        val list = filtered
            .orderBy(FeedbackTable.createdAt to SortOrder.DESC)
            .limit(pageSize)
            .offset((page - 1).toLong() * pageSize)
            .map { it.toFeedback() }
        total to list
    }

    // 筛选条件组合（PRD §2.5）：appName/appVersion 精确、category 枚举多选、status 精确、
    // createdAt 闭区间（解析器已折算含端点全天）、keyword LIKE 标题或内容；
    // 无任何筛选时返回 null（调用方不加 where，不做恒真表达式）
    private fun listFilterCondition(filter: FeedbackListFilter): Op<Boolean>? {
        val conditions = buildList<Op<Boolean>> {
            filter.appName?.let { add(FeedbackTable.appName eq it) }
            filter.appVersion?.let { add(FeedbackTable.appVersion eq it) }
            // 解析器保证 categories 非空；防御性跳过空列表，避免生成非法 IN () SQL
            filter.categories?.takeIf { it.isNotEmpty() }?.let { categories ->
                add(FeedbackTable.category inList categories.map { it.name })
            }
            filter.status?.let { add(FeedbackTable.status eq it.value.toByte()) }
            filter.startAt?.let { add(FeedbackTable.createdAt greaterEq it) }
            filter.endAt?.let { add(FeedbackTable.createdAt lessEq it) }
            filter.keyword?.let { keyword ->
                add((FeedbackTable.title like "%$keyword%") or (FeedbackTable.content like "%$keyword%"))
            }
        }
        return conditions.takeIf { it.isNotEmpty() }?.reduce { acc, condition -> acc and condition }
    }

    override suspend fun updateStatus(id: Long, status: FeedbackStatus, updatedAt: LocalDateTime): Boolean =
        newSuspendedTransaction(Dispatchers.IO) {
            FeedbackTable.update({ FeedbackTable.id eq id }) { row ->
                row[FeedbackTable.status] = status.value.toByte()
                row[FeedbackTable.updatedAt] = updatedAt
            } > 0
        }

    override suspend fun delete(id: Long): Boolean = newSuspendedTransaction(Dispatchers.IO) {
        FeedbackTable.deleteWhere { FeedbackTable.id eq id } > 0
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
