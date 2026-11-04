package com.caizhixing.feedback.repository

import com.caizhixing.feedback.model.Feedback
import com.caizhixing.feedback.model.FeedbackListFilter

/**
 * 反馈仓储（PRD §2.4 冻结签名）：唯一数据入口。
 * #5 已追加 findTotalAndList；#6 追加 updateStatus/delete —— 不得提前实现。
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
}
