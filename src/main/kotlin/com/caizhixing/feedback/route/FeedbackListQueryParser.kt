package com.caizhixing.feedback.route

import com.caizhixing.feedback.model.FeedbackCategory
import com.caizhixing.feedback.model.FeedbackListFilter
import com.caizhixing.feedback.model.FeedbackStatus
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/**
 * GET /api/feedback/list Query 参数解析纯函数（PRD §2.5 列表规则，issue #5）：
 * 非法值一律 40001 不静默纠正；page 默认 1、pageSize 默认 20（1–100）；
 * category 逗号分隔多选枚举（去重保序）；status 仅 "0"/"1"；日期 yyyy-MM-dd 严格解析
 * （STRICT 解析器拒收 2026-02-29 等不存在的日期），区间含端点全天（上海墙钟）；
 * keyword trim 后空 = 未传、限 50 字符。appName/appVersion 原样精确匹配（不 trim/不纠错）。
 * 入参为 query 单值（Ktor Parameters[key] 语义：重复参数取首个）。
 * message 逐字段按 "字段: 原因" 产出，返回首个错误（对齐 FeedbackValidator 范式）。
 */
object FeedbackListQueryParser {
    const val DEFAULT_PAGE: Int = 1
    const val DEFAULT_PAGE_SIZE: Int = 20
    const val PAGE_SIZE_MAX: Int = 100
    const val KEYWORD_MAX_LENGTH: Int = 50

    private val CATEGORY_VALUES: List<String> = FeedbackCategory.entries.map { it.name }
    private val CATEGORY_BY_NAME: Map<String, FeedbackCategory> =
        FeedbackCategory.entries.associateBy { it.name }

    // 正则先锁死 4-2-2 位数形态（拒收 "2026-1-2"），STRICT 解析器再拒收不存在的日期
    // （默认 SMART 会把 2026-02-29 静默滚成 2026-02-28，违反"不静默纠正"）
    private val DATE_SHAPE: Regex = Regex("""\d{4}-\d{2}-\d{2}""")
    private val STRICT_DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)
    private val END_OF_DAY: LocalTime = LocalTime.of(23, 59, 59)

    fun parse(
        page: String?,
        pageSize: String?,
        appName: String?,
        appVersion: String?,
        category: String?,
        status: String?,
        startDate: String?,
        endDate: String?,
        keyword: String?,
    ): FeedbackListQueryResult {
        val parsedPage = if (page == null) {
            DEFAULT_PAGE
        } else {
            page.toIntOrNull()?.takeIf { it >= 1 }
                ?: return FeedbackListQueryResult.Invalid("page: 必须是不小于 1 的整数")
        }
        val parsedPageSize = if (pageSize == null) {
            DEFAULT_PAGE_SIZE
        } else {
            pageSize.toIntOrNull()?.takeIf { it in 1..PAGE_SIZE_MAX }
                ?: return FeedbackListQueryResult.Invalid("pageSize: 必须是 1-$PAGE_SIZE_MAX 的整数")
        }

        // category：逗号分段逐个查枚举（大小写敏感、不 trim），任一非法即 40001；合法多选去重保序
        val categories: List<FeedbackCategory>? = if (category == null) {
            null
        } else {
            val resolved = mutableListOf<FeedbackCategory>()
            for (segment in category.split(",")) {
                val resolvedCategory = CATEGORY_BY_NAME[segment]
                    ?: return FeedbackListQueryResult.Invalid(
                        "category: 每个分段必须是 ${CATEGORY_VALUES.joinToString("/")} 之一",
                    )
                resolved += resolvedCategory
            }
            resolved.distinct()
        }

        // status：仅接受字面量 "0"/"1"（"01"、" 1"、布尔串等一律 40001）
        val statusFilter = when (status) {
            null -> null
            "0" -> FeedbackStatus.PENDING
            "1" -> FeedbackStatus.DONE
            else -> return FeedbackListQueryResult.Invalid("status: 必须是 0 或 1")
        }

        // 日期：null（未传）与解析失败（null 返回值）按入参是否为 null 区分，不静默纠正
        val start = startDate?.let(::parseStrictDate)
        if (startDate != null && start == null) {
            return FeedbackListQueryResult.Invalid("startDate: 日期格式必须为 yyyy-MM-dd")
        }
        val end = endDate?.let(::parseStrictDate)
        if (endDate != null && end == null) {
            return FeedbackListQueryResult.Invalid("endDate: 日期格式必须为 yyyy-MM-dd")
        }
        if (start != null && end != null && end < start) {
            return FeedbackListQueryResult.Invalid("endDate: 不能早于 startDate")
        }

        // keyword：trim 后空 = 未传；限长按 trim 后计
        var keywordFilter: String? = null
        if (keyword != null) {
            val trimmed = keyword.trim()
            if (trimmed.isNotEmpty() && trimmed.length > KEYWORD_MAX_LENGTH) {
                return FeedbackListQueryResult.Invalid(
                    "keyword: 长度不能超过 $KEYWORD_MAX_LENGTH 字符",
                )
            }
            keywordFilter = trimmed.takeIf { it.isNotEmpty() }
        }

        return FeedbackListQueryResult.Valid(
            filter = FeedbackListFilter(
                appName = appName,
                appVersion = appVersion,
                categories = categories,
                status = statusFilter,
                // 含端点全天（PRD §2.5）：startDate → 当日 00:00:00，endDate → 当日 23:59:59
                startAt = start?.atStartOfDay(),
                endAt = end?.atTime(END_OF_DAY),
                keyword = keywordFilter,
            ),
            page = parsedPage,
            pageSize = parsedPageSize,
        )
    }

    // 纯解析：形态不符或日期不存在（如 2026-02-29）返回 null，message 由调用方按字段产出
    private fun parseStrictDate(value: String): LocalDate? {
        if (!DATE_SHAPE.matches(value)) return null
        return try {
            LocalDate.parse(value, STRICT_DATE)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
