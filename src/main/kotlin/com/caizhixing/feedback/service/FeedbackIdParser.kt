package com.caizhixing.feedback.service

import com.caizhixing.feedback.model.ApiException
import com.caizhixing.feedback.model.ErrorCode

private const val ID_MUST_BE_NUMERIC_MESSAGE: String = "id: 必须为数字"

/**
 * 路径参数 id 解析纯函数（PRD §2.5：id 非数字 → 40001，行为 6.4）。
 * 严格 toLongOrNull 语义：不 trim、不接受小数/前后缀字符（全角数字按其值解析）；
 * 负数属合法数字，是否存在交由仓储层判定（未命中 → 40401）。
 */
internal fun parseFeedbackId(raw: String?): Long = raw?.toLongOrNull()
    ?: throw ApiException(ErrorCode.BAD_REQUEST, ID_MUST_BE_NUMERIC_MESSAGE)
