package com.nononsenseapps.feeder.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// === 多段事件数据结构 ===
/** 单个活动段，如展期、开幕、闭幕等 */
data class EventSegment(
    val name: String,
    val start: Long?,
    val end: Long?
) {
    fun toReadableString(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH)
        val startStr = start?.let { sdf.format(Date(it)) } ?: "未知"
        val endStr = end?.let { sdf.format(Date(it)) } ?: "未知"
        return "$name: $startStr → $endStr"
    }
}

/** 全部解析出的事件，包括主展期和各子活动 */
data class ParsedEvents(
    val exhibition: EventSegment?,
    val receptions: List<EventSegment>
) {
    fun isEmpty(): Boolean = exhibition == null && receptions.isEmpty()

    fun toReadableString(): String {
        val sb = StringBuilder()
        exhibition?.let { sb.append("主展览: ${it.toReadableString()}\n") }
        if (receptions.isNotEmpty()) {
            sb.append("活动:\n")
            receptions.forEach { sb.append("  - ${it.toReadableString()}\n") }
        }
        return sb.toString().trim()
    }
}

object TimeUtils {
    private val DEBUG = true // 控制调试输出

    /**
     * 向后兼容方法：返回原来的Pair格式
     * 这样现有的UI代码就不需要修改了
     */
    fun extractEventTimeRangeEnhancedLegacy(text: String): Pair<Long?, Long?> {
        val result = extractEventTimeRangeEnhanced(text)
        return Pair(result.exhibition?.start, result.exhibition?.end)
    }

    /**
     * 增强版解析：完全基于原始版本，保留所有有效模式
     */
    fun extractEventTimeRangeEnhanced(text: String): ParsedEvents {
        val now = Calendar.getInstance()
        val currentYear = now.get(Calendar.YEAR)

        // 创建局部格式化器，避免线程安全问题
        fun createDateTimeFormat() = SimpleDateFormat("MMMM d yyyy h:mma", Locale.ENGLISH)
        fun createDateTimeWithMinutesFormat() = SimpleDateFormat("MMMM d yyyy h:mm a", Locale.ENGLISH)
        fun createDateOnlyFormat() = SimpleDateFormat("MMMM d yyyy", Locale.ENGLISH)

        // 英文月份缩写映射
        val monthMap = mapOf(
            "jan" to "january", "feb" to "february", "mar" to "march",
            "apr" to "april", "may" to "may", "jun" to "june",
            "jul" to "july", "aug" to "august", "sep" to "september",
            "sept" to "september", "oct" to "october", "nov" to "november", "dec" to "december"
        )

        fun parseToMillis(dateStr: String): Long? {
            // 首先标准化时间格式：确保所有时间都有分钟部分
            val normalizedDateStr = dateStr.replace(Regex("(\\d{1,2})([AP]M)", RegexOption.IGNORE_CASE), "$1:00 $2")

            if (DEBUG) println("  尝试解析: '$dateStr' -> '$normalizedDateStr'")

            return try {
                // 尝试带分钟的格式 "June 24 2025 5:00 PM"
                createDateTimeWithMinutesFormat().parse(normalizedDateStr)?.time
            } catch (e: Exception) {
                try {
                    // 尝试不带分钟的格式 "June 24 2025 5PM"
                    createDateTimeFormat().parse(dateStr)?.time
                } catch (e2: Exception) {
                    try {
                        // 尝试只有日期的格式
                        createDateOnlyFormat().parse(dateStr)?.time
                    } catch (e3: Exception) {
                        if (DEBUG) println("  ❌ 解析失败: $dateStr")
                        null
                    }
                }
            }
        }

        // 添加调试输出
        if (DEBUG) {
            println("=== TimeUtils DEBUG ===")
            println("Original text: $text")
        }

        // 先检查原始文本中的分隔符模式（在预处理之前）
        val originalLowerText = text.lowercase(Locale.ENGLISH)

        // 处理月份缩写
        var tempText = originalLowerText
        monthMap.forEach { (abbr, full) ->
            tempText = tempText.replace(Regex("\\b$abbr\\b"), full)
        }
        tempText = tempText.replace(Regex("(\\d{1,2})(st|nd|rd|th)\\b"), "$1")  // 移除序数后缀

        // === 首先检查分隔符格式 ===
        // Format 0a: 分隔符 + 时间范围 "June 24 | 5-9pm", "May 3•5pm"
        val pattern0a = Regex("(\\w+\\s+\\d{1,2})(?:,?\\s*(\\d{4}))?\\s*[|:;•]\\s*(\\d{1,2}(?::\\d{2})?\\s*[ap]m)\\s*[-–]\\s*(\\d{1,2}(?::\\d{2})?\\s*[ap]m)")
        pattern0a.find(tempText)?.let {
            val (dateStr, yearStr, startStr, endStr) = it.destructured
            val year = yearStr.ifBlank { currentYear.toString() }
            val start = "$dateStr $year ${startStr.uppercase()}"
            val end = "$dateStr $year ${endStr.uppercase()}"
            if (DEBUG) println("Pattern0a matched (separator with time range): $dateStr, $year, $startStr, $endStr")
            val startMs = parseToMillis(start)
            val endMs = parseToMillis(end)
            if (startMs != null && endMs != null) {
                return ParsedEvents(EventSegment("活动", startMs, endMs), emptyList())
            }
        }

        // Format 0b: 分隔符 + 单个时间 "June 24 | 5pm"
        val pattern0b = Regex("(\\w+\\s+\\d{1,2})(?:,?\\s*(\\d{4}))?\\s*[|:;•]\\s*(\\d{1,2}(?::\\d{2})?\\s*[ap]m)")
        pattern0b.find(tempText)?.let {
            val (dateStr, yearStr, timeStr) = it.destructured
            val year = yearStr.ifBlank { currentYear.toString() }
            val start = "$dateStr $year ${timeStr.uppercase()}"
            val startMs = parseToMillis(start)
            if (DEBUG) println("Pattern0b matched (separator with single time): $dateStr, $year, $timeStr")
            if (startMs != null) {
                return ParsedEvents(EventSegment("活动", startMs, startMs + 2 * 60 * 60 * 1000), emptyList())
            }
        }

        // Format 0c: 带星期的分隔符格式 "Saturday, June 24 | 5-9pm"
        val pattern0c = Regex("(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|wed|thu|fri|sat|sun),?\\s*(\\w+\\s+\\d{1,2})(?:,?\\s*(\\d{4}))?\\s*[|:;•]\\s*(\\d{1,2}(?::\\d{2})?\\s*[ap]m)\\s*[-–]\\s*(\\d{1,2}(?::\\d{2})?\\s*[ap]m)")
        pattern0c.find(tempText)?.let {
            val (dateStr, yearStr, startStr, endStr) = it.destructured
            val year = yearStr.ifBlank { currentYear.toString() }
            val start = "$dateStr $year ${startStr.uppercase()}"
            val end = "$dateStr $year ${endStr.uppercase()}"
            if (DEBUG) println("Pattern0c matched (weekday + separator with time range): $dateStr, $year, $startStr, $endStr")
            val startMs = parseToMillis(start)
            val endMs = parseToMillis(end)
            if (startMs != null && endMs != null) {
                return ParsedEvents(EventSegment("活动", startMs, endMs), emptyList())
            }
        }

        // Format 0d: 前缀+分隔符 "Open Room•May 3, 2025•5pm"
        val pattern0d = Regex("\\w+\\s+\\w+\\s*[|:;•]\\s*(\\w+\\s+\\d{1,2})(?:,?\\s*(\\d{4}))?\\s*[|:;•]\\s*(\\d{1,2}(?::\\d{2})?\\s*[ap]m)")
        pattern0d.find(tempText)?.let {
            val (dateStr, yearStr, timeStr) = it.destructured
            val year = yearStr.ifBlank { currentYear.toString() }
            val start = "$dateStr $year ${timeStr.uppercase()}"
            val startMs = parseToMillis(start)
            if (DEBUG) println("Pattern0d matched (prefix + separator): $dateStr, $year, $timeStr")
            if (startMs != null) {
                return ParsedEvents(EventSegment("活动", startMs, startMs + 2 * 60 * 60 * 1000), emptyList())
            }
        }

        // 继续进行标准的文本预处理
        var cleanText = tempText
            .replace(Regex("–|—"), "-")  // 替换长破折号

            // 处理时间范围格式
            .replace(Regex(":\\s*(\\d{1,2})\\s*[-–]\\s*(\\d{1,2})\\s+(am|pm)"), " $1$3-$2$3")  // "November 23: 6 – 8 pm" -> "November 23 6pm-8pm"
            .replace(Regex("(\\d{1,2})\\s*[-–]\\s*(\\d{1,2})\\s+(am|pm)"), "$1$3-$2$3")  // "6 - 8 pm" -> "6pm-8pm"
            .replace(Regex("(\\d{1,2}:\\d{2})\\s*[-–]\\s*(\\d{1,2}:\\d{2})\\s+(am|pm)"), "$1$3-$2$3")  // "6:00 - 9:00 pm"

        if (DEBUG) println("Clean text: $cleanText")

        var exhibition: EventSegment? = null
        val receptions = mutableListOf<EventSegment>()

        // === 特殊格式处理：Opening Reception + 展览信息 ===

        // 处理 "Opening Reception: Saturday, March 08, 2025, 6:00 – 8:00 pm"
        val openingReceptionPattern = Regex(
            "opening reception:\\s*(?:saturday|sunday|monday|tuesday|wednesday|thursday|friday)?,?\\s*" +
                    "(\\w+\\s+\\d{1,2})(?:st|nd|rd|th)?,?\\s*(\\d{4}),?\\s*" +
                    "(\\d{1,2}:\\d{2})\\s*[-–]\\s*(\\d{1,2}:\\d{2})\\s*([ap]m)",
            RegexOption.IGNORE_CASE
        )
        openingReceptionPattern.find(cleanText)?.let { match ->
            val (dateStr, yearStr, startTime, endTime, ampm) = match.destructured
            val startMs = parseToMillis("$dateStr $yearStr $startTime ${ampm.uppercase()}")
            val endMs = parseToMillis("$dateStr $yearStr $endTime ${ampm.uppercase()}")
            if (DEBUG) println("✅ Opening Reception找到: $dateStr $yearStr $startTime-$endTime $ampm")
            if (startMs != null && endMs != null) {
                receptions.add(EventSegment("Opening Reception", startMs, endMs))
            }
        }

        // 处理 "Visit: March 08 – 30, 2025" 或 "Dates: June 07 – 29, 2025"
        val visitDatesPattern = Regex(
            "(?:visit|dates):\\s*(\\w+)\\s+(\\d{1,2})(?:st|nd|rd|th)?\\s*[-–]\\s*(\\d{1,2}),?\\s*(\\d{4})",
            RegexOption.IGNORE_CASE
        )
        visitDatesPattern.find(cleanText)?.let { match ->
            val (month, startDay, endDay, year) = match.destructured
            val startMs = parseToMillis("$month $startDay $year")
            val endMs = parseToMillis("$month $endDay $year")
            if (DEBUG) println("✅ Visit/Dates找到: $month $startDay-$endDay $year")
            if (startMs != null && endMs != null) {
                exhibition = EventSegment("展览", startMs, endMs)
            }
        }

        // 处理 "Opens on July 12, 2024" + "On view through July 28, 2024"
        var opensDate: Long? = null
        var throughDate: Long? = null

        val opensPattern = Regex(
            "opens on\\s+(\\w+\\s+\\d{1,2})(?:st|nd|rd|th)?,?\\s*(\\d{4})",
            RegexOption.IGNORE_CASE
        )
        opensPattern.find(cleanText)?.let { match ->
            val (dateStr, yearStr) = match.destructured
            opensDate = parseToMillis("$dateStr $yearStr")
            if (DEBUG) println("✅ Opens找到: $dateStr $yearStr")
        }

        val throughPattern = Regex(
            "(?:on view through|through)\\s+(\\w+\\s+\\d{1,2})(?:st|nd|rd|th)?,?\\s*(\\d{4})",
            RegexOption.IGNORE_CASE
        )
        throughPattern.find(cleanText)?.let { match ->
            val (dateStr, yearStr) = match.destructured
            throughDate = parseToMillis("$dateStr $yearStr")
            if (DEBUG) println("✅ Through找到: $dateStr $yearStr")
        }

        if (opensDate != null || throughDate != null) {
            exhibition = EventSegment("展览", opensDate, throughDate)
        }

        // 处理简单的 "Opening reception: July 12, 6:00-8:00pm"
        if (receptions.isEmpty()) {
            val simpleReceptionPattern = Regex(
                "opening reception:\\s*(\\w+\\s+\\d{1,2})(?:st|nd|rd|th)?,?\\s*(?:(\\d{4}),?\\s*)?" +
                        "(\\d{1,2}:\\d{2})\\s*[-–]\\s*(\\d{1,2}:\\d{2})\\s*([ap]m)",
                RegexOption.IGNORE_CASE
            )
            simpleReceptionPattern.find(cleanText)?.let { match ->
                val (dateStr, yearStr, startTime, endTime, ampm) = match.destructured
                val year = yearStr.ifBlank {
                    // 如果没有年份，尝试从opensDate推断
                    if (opensDate != null) {
                        val cal = Calendar.getInstance()
                        cal.timeInMillis = opensDate!!
                        cal.get(Calendar.YEAR).toString()
                    } else {
                        currentYear.toString()
                    }
                }
                val startMs = parseToMillis("$dateStr $year $startTime ${ampm.uppercase()}")
                val endMs = parseToMillis("$dateStr $year $endTime ${ampm.uppercase()}")
                if (DEBUG) println("✅ Simple Opening Reception找到: $dateStr $year $startTime-$endTime $ampm")
                if (startMs != null && endMs != null) {
                    receptions.add(EventSegment("Opening Reception", startMs, endMs))
                }
            }
        }

        // 如果已经找到了exhibition或reception，直接返回
        if (exhibition != null || receptions.isNotEmpty()) {
            return ParsedEvents(exhibition, receptions)
        }

        // Format 1a: 带星期、有年份的具体时间 "saturday, february 1, 2025, 6pm-8pm"
        val pattern1a = Regex("(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|wed|thu|fri|sat|sun),?\\s*(\\w+\\s+\\d{1,2}),?\\s*(\\d{4}),?\\s*(?:at\\s+)?(\\d{1,2}(?::\\d{2})?\\s*[ap]m)\\s*(?:to|[-–])\\s*(\\d{1,2}(?::\\d{2})?\\s*[ap]m)")
        pattern1a.find(cleanText)?.let {
            val (dateStr, yearStr, startStr, endStr) = it.destructured
            val start = "$dateStr $yearStr ${startStr.uppercase()}"
            val end = "$dateStr $yearStr ${endStr.uppercase()}"
            if (DEBUG) println("Pattern1a matched: $dateStr, $yearStr, $startStr, $endStr")
            val startMs = parseToMillis(start)
            val endMs = parseToMillis(end)
            if (startMs != null && endMs != null) {
                return ParsedEvents(EventSegment("活动", startMs, endMs), emptyList())
            }
        }

        // Format 1b: 带星期、无年份的具体时间 "saturday, april 5, 6pm-8pm"
        val pattern1b = Regex("(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|wed|thu|fri|sat|sun),?\\s*(\\w+\\s+\\d{1,2}),?\\s*(?:at\\s+)?(\\d{1,2}(?::\\d{2})?\\s*[ap]m)\\s*(?:to|[-–])\\s*(\\d{1,2}(?::\\d{2})?\\s*[ap]m)")
        pattern1b.find(cleanText)?.let {
            val (dateStr, startStr, endStr) = it.destructured
            val start = "$dateStr $currentYear ${startStr.uppercase()}"
            val end = "$dateStr $currentYear ${endStr.uppercase()}"
            if (DEBUG) println("Pattern1b matched: $dateStr, $startStr, $endStr")
            val startMs = parseToMillis(start)
            val endMs = parseToMillis(end)
            if (startMs != null && endMs != null) {
                return ParsedEvents(EventSegment("活动", startMs, endMs), emptyList())
            }
        }

        // Format 1c: 不带星期、有年份的具体时间 "february 1, 2025, 6pm-8pm"
        val pattern1c = Regex("(\\w+\\s+\\d{1,2}),?\\s*(\\d{4}),?\\s*(?:at\\s+)?(\\d{1,2}(?::\\d{2})?\\s*[ap]m)\\s*(?:to|[-–])\\s*(\\d{1,2}(?::\\d{2})?\\s*[ap]m)")
        pattern1c.find(cleanText)?.let {
            val (dateStr, yearStr, startStr, endStr) = it.destructured
            val start = "$dateStr $yearStr ${startStr.uppercase()}"
            val end = "$dateStr $yearStr ${endStr.uppercase()}"
            if (DEBUG) println("Pattern1c matched: $dateStr, $yearStr, $startStr, $endStr")
            val startMs = parseToMillis(start)
            val endMs = parseToMillis(end)
            if (startMs != null && endMs != null) {
                return ParsedEvents(EventSegment("活动", startMs, endMs), emptyList())
            }
        }

        // Format 1d: 不带星期、无年份的具体时间 "april 5, 6pm-8pm"
        val pattern1d = Regex("(\\w+\\s+\\d{1,2}),?\\s*(?:at\\s+)?(\\d{1,2}(?::\\d{2})?\\s*[ap]m)\\s*(?:to|[-–])\\s*(\\d{1,2}(?::\\d{2})?\\s*[ap]m)")
        pattern1d.find(cleanText)?.let {
            val (dateStr, startStr, endStr) = it.destructured
            val start = "$dateStr $currentYear ${startStr.uppercase()}"
            val end = "$dateStr $currentYear ${endStr.uppercase()}"
            if (DEBUG) println("Pattern1d matched: $dateStr, $startStr, $endStr")
            val startMs = parseToMillis(start)
            val endMs = parseToMillis(end)
            if (startMs != null && endMs != null) {
                return ParsedEvents(EventSegment("活动", startMs, endMs), emptyList())
            }
        }

        // Format 2a: "from february 1 to february 23"
        val pattern2a = Regex("from\\s+(\\w+\\s+\\d{1,2})(?:,?\\s*(\\d{4}))?\\s+(?:to|[-–])\\s+(\\w+\\s+\\d{1,2})(?:,?\\s*(\\d{4}))?")
        pattern2a.find(cleanText)?.let {
            val (startDate, startYear, endDate, endYear) = it.destructured
            val sy = startYear.ifBlank { currentYear.toString() }
            val ey = if (endYear.isNotBlank()) endYear else sy
            if (DEBUG) println("Pattern2a matched: $startDate, $startYear, $endDate, $endYear")
            val startMs = parseToMillis("$startDate $sy")
            val endMs = parseToMillis("$endDate $ey")
            if (startMs != null && endMs != null) {
                return ParsedEvents(EventSegment("展览", startMs, endMs), emptyList())
            }
        }

        // Format 2b: "november 22, 2024 – january 5, 2025" (不带from)
        val pattern2b = Regex("(\\w+\\s+\\d{1,2}),?\\s*(\\d{4})\\s*[-–]\\s*(\\w+\\s+\\d{1,2}),?\\s*(\\d{4})")
        pattern2b.find(cleanText)?.let {
            val (startDate, startYear, endDate, endYear) = it.destructured
            if (DEBUG) println("Pattern2b matched: $startDate, $startYear, $endDate, $endYear")
            val startMs = parseToMillis("$startDate $startYear")
            val endMs = parseToMillis("$endDate $endYear")
            if (startMs != null && endMs != null) {
                return ParsedEvents(EventSegment("展览", startMs, endMs), emptyList())
            }
        }

        // Format 2c: "february 1-23, 2025" (同月日期范围)
        val pattern2c = Regex("(\\w+)\\s+(\\d{1,2})[-–](\\d{1,2}),?\\s*(\\d{4})")
        pattern2c.find(cleanText)?.let {
            val (month, startDay, endDay, year) = it.destructured
            if (DEBUG) println("Pattern2c matched: $month, $startDay, $endDay, $year")
            val startMs = parseToMillis("$month $startDay $year")
            val endMs = parseToMillis("$month $endDay $year")
            if (startMs != null && endMs != null) {
                return ParsedEvents(EventSegment("展览", startMs, endMs), emptyList())
            }
        }

        // Format 2d: "october 5–november 10, 2024" (跨月日期范围)
        val pattern2d = Regex("(\\w+\\s+\\d{1,2})[-–](\\w+\\s+\\d{1,2}),?\\s*(\\d{4})")
        pattern2d.find(cleanText)?.let {
            val (startDate, endDate, year) = it.destructured
            if (DEBUG) println("Pattern2d matched: $startDate, $endDate, $year")
            val startMs = parseToMillis("$startDate $year")
            val endMs = parseToMillis("$endDate $year")
            if (startMs != null && endMs != null) {
                return ParsedEvents(EventSegment("展览", startMs, endMs), emptyList())
            }
        }

        // Format 3: 提交截止时间 "thursday, october 31, 2024 at 11:59 pm est"
        val pattern3deadline = Regex("(?:deadline|due):\\s*(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|wed|thu|fri|sat|sun),?\\s*(\\w+\\s+\\d{1,2}),?\\s*(\\d{4})\\s*(?:at\\s+)?(\\d{1,2}:\\d{2})\\s*([ap]m)")
        pattern3deadline.find(cleanText)?.let {
            val (date, year, time, ampm) = it.destructured
            val dateTime = "$date $year ${time.uppercase()}${ampm.uppercase()}"
            val startMs = parseToMillis(dateTime)
            if (DEBUG) println("Pattern3deadline matched: $date, $year, $time, $ampm")
            if (startMs != null) {
                return ParsedEvents(EventSegment("截止时间", startMs, startMs), emptyList())
            }
        }

        // Format 4a: Single datetime with weekday, e.g. "saturday, april 5 at 6:30pm"
        val pattern4a = Regex("(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|wed|thu|fri|sat|sun),?\\s*(\\w+\\s+\\d{1,2})(?:,?\\s*(\\d{4}))?\\s*,?\\s*(?:at\\s+)?(\\d{1,2}(?::\\d{2})?\\s*[ap]m)")
        pattern4a.find(cleanText)?.let {
            val (date, year, time) = it.destructured
            val y = year.ifBlank { currentYear.toString() }
            val start = "$date $y ${time.uppercase()}"
            val startMs = parseToMillis(start)
            if (DEBUG) println("Pattern4a matched: $date, $y, $time")
            if (startMs != null) {
                return ParsedEvents(EventSegment("活动", startMs, startMs + 2 * 60 * 60 * 1000), emptyList())
            }
        }

        // Format 4b: Single datetime without weekday, e.g. "april 5 at 6:30pm"
        val pattern4b = Regex("(\\w+\\s+\\d{1,2})(?:,?\\s*(\\d{4}))?\\s*,?\\s*(?:at\\s+)?(\\d{1,2}(?::\\d{2})?\\s*[ap]m)")
        pattern4b.find(cleanText)?.let {
            val (date, year, time) = it.destructured
            val y = year.ifBlank { currentYear.toString() }
            val start = "$date $y ${time.uppercase()}"
            val startMs = parseToMillis(start)
            if (DEBUG) println("Pattern4b matched: $date, $y, $time")
            if (startMs != null) {
                return ParsedEvents(EventSegment("活动", startMs, startMs + 2 * 60 * 60 * 1000), emptyList())
            }
        }

        if (DEBUG) {
            println("❌ 未识别到任何时间信息")
            println("=======================")
        }

        return ParsedEvents(null, emptyList())
    }

    // 测试函数
    fun testTimeExtraction() {
        val testCases = listOf(
            // 用户提供的新测试用例
            "Opening Reception: Saturday, March 08, 2025, 6:00 – 8:00 pmVisit: March 08 – 30, 2025, Friday – Sunday 1:00 – 6 pm. Other days by appointment.",
            "Opening Reception: Saturday, June 7th, 2025, 6:00 – 9:00 pmDates: June 07 – 29, 2025, Thursday – Sunday 1:00 – 6 pm. Other days by appointment.",
            "Opens on July 12, 2024 at the PS 122 Gallery,On view through July 28, 2024.Opening reception: July 12, 6:00-8:00pm",

            // 分隔符格式测试
            "June 24 | 5-9pm",
            "June 24 | 5pm",
            "Saturday, June 24 | 5-9pm",
            "November 23: 6 – 8 pm",
            "April 5; 2-4pm",
            "Open Room•May 3, 2025•5pm",
            "May 3, 2025•5pm",

            // 原有格式测试
            "Saturday, February 1, 2025 6-8 pm",
            "November 22, 2024 – January 5, 2025",
            "October 5–November 10, 2024",
            "Thursday, October 31, 2024 at 11:59 pm EST",
            "February 1-23, 2025",
            "Saturday, April 5 at 6:30pm",
            "April 5 at 6:30pm",
            "deadline: saturday, april 5, 2025 at 6:30pm"
        )

        println("=== TimeUtils 完整测试 ===")
        testCases.forEachIndexed { index, testCase ->
            println("\n测试案例 ${index + 1}: $testCase")
            val result = extractEventTimeRangeEnhanced(testCase)
            if (!result.isEmpty()) {
                println(result.toReadableString())
            } else {
                println("❌ 解析失败")
            }
            println("-".repeat(60))
        }
    }
}
