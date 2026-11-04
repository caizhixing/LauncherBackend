package com.caizhixing.feedback.route

import com.caizhixing.feedback.model.FeedbackCategory
import com.caizhixing.feedback.model.FeedbackStatus
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * 行为 5.1–5.6（PRD §2.5 列表规则）：解析纯函数全分支覆盖（含全部非法分支，目标 100% 行覆盖）。
 * 非法值一律 Invalid（route 层转 40001），不静默纠正；message 按 "字段: 原因" 产出。
 */
class FeedbackListQueryParserTest {

    private fun parse(
        page: String? = null,
        pageSize: String? = null,
        appName: String? = null,
        appVersion: String? = null,
        category: String? = null,
        status: String? = null,
        startDate: String? = null,
        endDate: String? = null,
        keyword: String? = null,
    ): FeedbackListQueryResult =
        FeedbackListQueryParser.parse(
            page, pageSize, appName, appVersion, category, status, startDate, endDate, keyword,
        )

    private fun parseValid(vararg kwargs: Pair<String, String>): FeedbackListQueryResult.Valid {
        val allowed = setOf(
            "page", "pageSize", "appName", "appVersion", "category", "status", "startDate", "endDate", "keyword",
        )
        require(kwargs.all { it.first in allowed })
        val byName = kwargs.toMap()
        val result = parse(
            page = byName["page"],
            pageSize = byName["pageSize"],
            appName = byName["appName"],
            appVersion = byName["appVersion"],
            category = byName["category"],
            status = byName["status"],
            startDate = byName["startDate"],
            endDate = byName["endDate"],
            keyword = byName["keyword"],
        )
        return assertIs<FeedbackListQueryResult.Valid>(result)
    }

    private fun invalidMessage(result: FeedbackListQueryResult): String =
        assertIs<FeedbackListQueryResult.Invalid>(result).message

    // 行为 5.1：page 默认 1、pageSize 默认 20；越界/非数字 → 40001
    @Test
    fun paginationBounds() {
        val defaults = parseValid()
        assertEquals(1, defaults.page)
        assertEquals(20, defaults.pageSize)

        assertEquals(3, parseValid("page" to "3").page)
        assertEquals(50, parseValid("pageSize" to "50").pageSize)
        assertEquals(1, parseValid("pageSize" to "1").pageSize) // 下边界合法
        assertEquals(100, parseValid("pageSize" to "100").pageSize) // 上边界合法

        for (bad in listOf("0", "-2", "abc", "1.5", " 1")) {
            assertEquals("page: 必须是不小于 1 的整数", invalidMessage(parse(page = bad)), "page=$bad")
        }
        for (bad in listOf("0", "101", "-5", "abc", "20.0")) {
            assertEquals("pageSize: 必须是 1-100 的整数", invalidMessage(parse(pageSize = bad)), "pageSize=$bad")
        }
    }

    // 行为 5.2：category 任一段非枚举 → 40001；合法多选去重保序
    @Test
    fun categoryParsing() {
        assertNull(parseValid().filter.categories) // 未传 = 不筛选

        assertEquals(listOf(FeedbackCategory.BUG), parseValid("category" to "BUG").filter.categories)
        // 保序
        assertEquals(
            listOf(FeedbackCategory.SUGGESTION, FeedbackCategory.BUG, FeedbackCategory.OTHER),
            parseValid("category" to "SUGGESTION,BUG,OTHER").filter.categories,
        )
        // 去重保序（保留首次出现）
        assertEquals(
            listOf(FeedbackCategory.BUG, FeedbackCategory.EXPERIENCE),
            parseValid("category" to "BUG,EXPERIENCE,BUG").filter.categories,
        )
        assertEquals(
            listOf(FeedbackCategory.BUG),
            parseValid("category" to "BUG,BUG").filter.categories,
        )

        // 任一段非法（含空段/大小写/空白）→ 40001，不静默丢弃非法段
        val expected = "category: 每个分段必须是 BUG/EXPERIENCE/SUGGESTION/OTHER 之一"
        for (bad in listOf("NOPE", "BUG,NOPE", "", "BUG,", ",BUG", "bug", " BUG", "BUG ,EXPERIENCE", "0")) {
            assertEquals(expected, invalidMessage(parse(category = bad)), "category=$bad")
        }
    }

    // 行为 5.3：status 仅接受字面量 0/1
    @Test
    fun statusParsing() {
        assertNull(parseValid().filter.status)

        assertEquals(FeedbackStatus.PENDING, parseValid("status" to "0").filter.status)
        assertEquals(FeedbackStatus.DONE, parseValid("status" to "1").filter.status)

        for (bad in listOf("2", "-1", "abc", "", "01", " 1", "true", "1.0")) {
            assertEquals("status: 必须是 0 或 1", invalidMessage(parse(status = bad)), "status=$bad")
        }
    }

    // 行为 5.4：非 yyyy-MM-dd（形态/不存在日期）与 endDate<startDate → 40001
    @Test
    fun dateRangeValidation() {
        val start = parseValid("startDate" to "2026-11-04").filter.startAt
        assertEquals(LocalDateTime.of(2026, 11, 4, 0, 0, 0), start)
        val leap = parseValid("endDate" to "2024-02-29").filter.endAt // 闰年闰日合法
        assertEquals(LocalDateTime.of(2024, 2, 29, 23, 59, 59), leap)

        // 形态非法：位数、分隔符、空白
        for (bad in listOf("2026-11-4", "2026-1-04", "2026/11/04", "20261104", " 2026-11-04", "2026-11-04 ")) {
            assertEquals(
                "startDate: 日期格式必须为 yyyy-MM-dd",
                invalidMessage(parse(startDate = bad)),
                "startDate=$bad",
            )
        }
        // 日期不存在（STRICT 解析拒收，不静默滚动）
        for (bad in listOf("2026-02-29", "2026-13-01", "2026-11-31", "2026-00-10", "2026-11-00")) {
            assertEquals(
                "endDate: 日期格式必须为 yyyy-MM-dd",
                invalidMessage(parse(endDate = bad)),
                "endDate=$bad",
            )
        }

        // endDate < startDate → 40001；相等合法；单边合法（开区间）
        assertEquals(
            "endDate: 不能早于 startDate",
            invalidMessage(parse(startDate = "2026-11-05", endDate = "2026-11-04")),
        )
        parseValid("startDate" to "2026-11-04", "endDate" to "2026-11-04")
        parseValid("startDate" to "2026-11-04")
        parseValid("endDate" to "2026-11-04")
    }

    // 行为 5.5：区间语义 [当日 00:00:00, 当日 23:59:59]（上海墙钟，PRD §2.5）
    @Test
    fun dateRangeInclusiveBounds() {
        val multiDay = parseValid("startDate" to "2026-11-04", "endDate" to "2026-11-06").filter
        assertEquals(LocalDateTime.of(2026, 11, 4, 0, 0, 0), multiDay.startAt)
        assertEquals(LocalDateTime.of(2026, 11, 6, 23, 59, 59), multiDay.endAt)

        val sameDay = parseValid("startDate" to "2026-11-04", "endDate" to "2026-11-04").filter
        assertEquals(LocalDateTime.of(2026, 11, 4, 0, 0, 0), sameDay.startAt)
        assertEquals(LocalDateTime.of(2026, 11, 4, 23, 59, 59), sameDay.endAt)

        // 年份边界（含闰日当天全天）
        val leapDay = parseValid("startDate" to "2024-02-29", "endDate" to "2024-02-29").filter
        assertEquals(LocalDateTime.of(2024, 2, 29, 0, 0, 0), leapDay.startAt)
        assertEquals(LocalDateTime.of(2024, 2, 29, 23, 59, 59), leapDay.endAt)
    }

    // 行为 5.6：keyword trim 后空 = 未传；>50 → 40001（限长按 trim 后计）
    @Test
    fun keywordHandling() {
        assertNull(parseValid().filter.keyword)
        assertNull(parseValid("keyword" to "").filter.keyword)
        assertNull(parseValid("keyword" to "   ").filter.keyword)

        assertEquals("崩溃", parseValid("keyword" to "崩溃").filter.keyword)
        assertEquals("崩溃", parseValid("keyword" to "  崩溃\t").filter.keyword)

        assertEquals(50, parseValid("keyword" to "a".repeat(50)).filter.keyword?.length)
        assertEquals(
            "keyword: 长度不能超过 50 字符",
            invalidMessage(parse(keyword = "a".repeat(51))),
        )
        assertEquals(
            "keyword: 长度不能超过 50 字符",
            invalidMessage(parse(keyword = " " + "a".repeat(51) + " ")), // trim 后仍 51
        )
        assertEquals(50, parseValid("keyword" to " " + "a".repeat(50) + " ").filter.keyword?.length) // trim 后压线合法
    }

    // 行为 5.7 前置：appName/appVersion 原样精确透传（不 trim/不纠错）；全字段组合解析
    @Test
    fun fullValidQueryParsesAllFilterFields() {
        val valid = parseValid(
            "page" to "2",
            "pageSize" to "15",
            "appName" to "launcher",
            "appVersion" to "1.2.3",
            "category" to "BUG,OTHER",
            "status" to "1",
            "startDate" to "2026-11-01",
            "endDate" to "2026-11-30",
            "keyword" to "闪退",
        )

        assertEquals(2, valid.page)
        assertEquals(15, valid.pageSize)
        val filter = valid.filter
        assertEquals("launcher", filter.appName)
        assertEquals("1.2.3", filter.appVersion)
        assertEquals(listOf(FeedbackCategory.BUG, FeedbackCategory.OTHER), filter.categories)
        assertEquals(FeedbackStatus.DONE, filter.status)
        assertEquals(LocalDateTime.of(2026, 11, 1, 0, 0, 0), filter.startAt)
        assertEquals(LocalDateTime.of(2026, 11, 30, 23, 59, 59), filter.endAt)
        assertEquals("闪退", filter.keyword)
    }
}
