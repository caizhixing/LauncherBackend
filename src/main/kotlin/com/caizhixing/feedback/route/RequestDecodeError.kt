package com.caizhixing.feedback.route

import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.SerializationException

/**
 * 请求体反序列化失败 → 40001 message（PRD §2.5：指明字段与原因）。
 * 缺必填字段的常规路径已由 DTO 可空建模 + FeedbackValidator 逐字段出 message 覆盖
 * （如 "title: 必填"），本函数仅兜底仍会抛异常的解码失败：
 * - MissingFieldException：列出缺失字段名；
 * - 其余 SerializationException（字段类型不匹配等）：透出 kotlinx 原因（含 $.字段 路径）；
 * - 无序列化异常可提取：按非法 JSON 归类。
 */
internal fun requestDecodeErrorMessage(cause: Throwable): String {
    val serialization = generateSequence<Throwable>(cause) { current ->
        current.cause?.takeIf { it !== current }
    }.filterIsInstance<SerializationException>().firstOrNull()
    return when (serialization) {
        is MissingFieldException -> "缺少必填字段: ${serialization.missingFields.joinToString("、")}"
        null -> "请求体格式错误：非法 JSON"
        else -> "请求体格式错误：${serialization.message ?: "反序列化失败"}"
    }
}
