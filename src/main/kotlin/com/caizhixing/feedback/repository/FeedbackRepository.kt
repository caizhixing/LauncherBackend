package com.caizhixing.feedback.repository

import com.caizhixing.feedback.model.Feedback
import com.caizhixing.feedback.model.FeedbackListFilter
import com.caizhixing.feedback.model.FeedbackStatus
import java.time.LocalDateTime

/**
 * 反馈仓储（PRD §2.4 冻结签名）：唯一数据入口。
 * #5 已追加 findTotalAndList；#6 追加 updateStatus/delete。
 */
interface FeedbackRepository {
    suspend fun insert(feedback: Feedback): Long

    suspend fun findById(id: Long): Feedback?

    // #5：分页 + 筛选查询；total 为满足筛选条件的真实总数（与分页无关），
    // page 超界返回空 list（offset 越界自然无行），total 仍为真实值
    suspend fun findTotalAndList(
        filter: FeedbackListFilter,
        page: Int,
        pageSize: Int,
    ): Pair<Long, List<Feedback>>

    /** #6 冻结签名：更新处理状态并刷新 updated_at，返回是否命中已存在行。 */
    suspend fun updateStatus(id: Long, status: FeedbackStatus, updatedAt: LocalDateTime): Boolean

    /** #6 冻结签名：物理删除（PRD §2.5，非软删除），返回是否命中已存在行。 */
    suspend fun delete(id: Long): Boolean
}
