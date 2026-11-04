package com.caizhixing.feedback.repository

import com.caizhixing.feedback.model.Feedback

/**
 * 反馈仓储（PRD §2.4 冻结签名）：唯一数据入口。
 * #5 追加 findTotalAndList；#6 追加 updateStatus/delete —— 本单不得提前实现。
 */
interface FeedbackRepository {
    suspend fun insert(feedback: Feedback): Long

    suspend fun findById(id: Long): Feedback?
}
