package com.nononsenseapps.feeder.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.kodein.di.android.x.AndroidLifecycleScope
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// === 事件数据结构（确保只定义一次，避免与SitemapParser冲突） ===

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

/** 网页抓取配置 */
data class WebScrapingConfig(
    val enableWebScraping: Boolean = true,
    val cacheEnabled: Boolean = true,
    val timeout: Int = 10000,
    val userAgent: String = "Mozilla/5.0 (Android 10; Mobile; rv:91.0) Gecko/91.0 Firefox/91.0",
    val maxConcurrentRequests: Int = 3,
    val retryAttempts: Int = 2
)

object TimeUtils {
    // 新增：全局 fallbackYear，用于 parseToMillis 自动补全年份
    var globalFallbackYear: Int? = null
    private val DEBUG = true // 控制调试输出

    // 网页抓取缓存
    private val webCache = ConcurrentHashMap<String, ParsedEvents>()
    private var scrapingConfig = WebScrapingConfig()

    /**
     * 向后兼容方法：返回原来的Pair格式
     * 这样现有的UI代码就不需要修改了
     */
    fun extractEventTimeRangeEnhancedLegacy(text: String): Pair<Long?, Long?> {
        val result = extractEventTimeRangeEnhanced(text)
        return Pair(result.exhibition?.start, result.exhibition?.end)
    }

    /**
     * 增强版解析：支持RSS内容和可选的网页抓取
     */
    fun extractEventTimeRangeEnhanced(
        text: String,
        originalUrl: String? = null,
        enableWebScraping: Boolean = false
    ): ParsedEvents {
        // 首先尝试从文本内容提取
        val textResult = extractEventTimeFromText(text)

        if (!textResult.isEmpty()) {
            if (DEBUG) println("✅ 文本中找到时间信息")
            return textResult
        }

        // 如果文本中没有时间信息且启用网页抓取，尝试从原网页提取
        if (enableWebScraping && !originalUrl.isNullOrBlank()) {
            if (DEBUG) println("🔄 文本中未找到时间，尝试抓取原网页: $originalUrl")
            // 注意：这里需要在协程中调用
            // 在实际使用中，应该用suspend函数版本
            return ParsedEvents(null, emptyList()) // 同步版本的占位符
        }

        return ParsedEvents(null, emptyList())
    }

    /**
     * 协程版本：支持网页抓取的完整版本
     */
    suspend fun extractEventTimeRangeEnhancedAsync(
        text: String,
        originalUrl: String? = null,
        enableWebScraping: Boolean = false
    ): ParsedEvents {
        // 首先尝试从文本内容提取
        val textResult = extractEventTimeFromText(text)

        if (!textResult.isEmpty()) {
            if (DEBUG) println("✅ 文本中找到时间信息")
            return textResult
        }

        // 如果文本中没有时间信息且启用网页抓取，尝试从原网页提取
        if (enableWebScraping && !originalUrl.isNullOrBlank()) {
            if (DEBUG) println("🔄 文本中未找到时间，尝试抓取原网页: $originalUrl")
            return extractFromWebPage(originalUrl)
        }

        return ParsedEvents(null, emptyList())
    }

    /**
     * 解析日期字符串为毫秒时间戳
     */
    private fun parseToMillis(dateStr: String, fallbackYear: Int? = globalFallbackYear): Long? {
        // 预处理：处理缩写格式
        val cleaned = dateStr
            .replace("  ", " ")
            .replace(Regex("(st|nd|rd|th),"), ",")
            .replace(Regex("(st|nd|rd|th) "), " ")
            .replace(Regex("(\\d{1,2})p\\b"), "$1pm")  // 7:30p -> 7:30pm
            .replace(Regex("(\\d{1,2}:\\d{2})p\\b"), "$1pm")  // 处理缩写pm
            .trim()

        if (DEBUG) println("🔧 parseToMillis: '$dateStr' -> '$cleaned'")

        // 使用用户所在区域的时区，避免UTC偏移问题
        val userTimeZone = java.util.TimeZone.getDefault()

        // 常见国际、RSS、ISO格式
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss'Z'", // 2024-04-05T18:30:00Z (这个保持UTC)
            "yyyy-MM-dd'T'HH:mm:ssZ",   // 2024-04-05T18:30:00+0000 (这个保持UTC)
            "yyyy-MM-dd HH:mm:ss",      // 2024-04-05 18:30:00
            "yyyy-MM-dd",               // 2024-04-05
            "yyyy/MM/dd HH:mm",         // 2024/04/05 18:30
            "yyyy/MM/dd",               // 2024/04/05
            "MMM d yyyy h:mma",         // Apr 5 2024 6:30PM
            "MMMM d yyyy h:mma",        // April 5 2024 6:30PM
            "MMM d, yyyy",              // Apr 5, 2024
            "MMMM d, yyyy",             // April 5, 2024
            "MMMM d yyyy",              // April 5 2024
            "MMM d yyyy",               // Apr 5 2024
            "MMMM d, yyyy h:mma",       // April 5, 2024 6:30PM
            "MMM d, yyyy h:mma",        // Apr 5, 2024 6:30PM
            "MMM d, yyyy 'at' h:mm a",  // Jul 17, 2025 at 7:30 pm
            "MMMM d, yyyy 'at' h:mm a", // June 17, 2025 at 7:30 pm
            "MMMM d h:mma yyyy",        // April 5 6:30PM 2024
            "MMM d h:mma yyyy",         // Apr 5 6:30PM 2024
            "MMMM d, h:mma yyyy",       // April 5, 6:30PM 2024
            "MMM d, h:mma yyyy",        // Apr 5, 6:30PM 2024
            // 兼容没有年份的（用 fallbackYear）
            "MMMM d h:mma",             // April 5 6:30PM
            "MMM d h:mma",              // Apr 5 6:30PM
            "MMMM d",                   // April 5
            "MMM d",                    // Apr 5
            // 24小时制
            "yyyy-MM-dd'T'HH:mm:ss",    // 2024-04-05T18:30:00
            "yyyy-MM-dd'T'HH:mm",       // 2024-04-05T18:30
            "yyyy-MM-dd HH:mm",         // 2024-04-05 18:30
            "yyyy/MM/dd HH:mm:ss",      // 2024/04/05 18:30:00
            "dd MMM yyyy HH:mm:ss",     // 05 Apr 2024 18:30:00
            "dd MMM yyyy HH:mm",        // 05 Apr 2024 18:30
            "EEE, dd MMM yyyy HH:mm:ss Z", // RFC822/RSS: Fri, 05 Apr 2024 18:30:00 +0000
            "EEE, dd MMM yyyy HH:mm:ss z", // Fri, 05 Apr 2024 18:30:00 GMT
            "EEE, dd MMM yyyy HH:mm zzz",  // Fri, 05 Apr 2024 18:30 GMT
            "EEE, dd MMM yyyy HH:mm",      // Fri, 05 Apr 2024 18:30
        )

        // 先尝试所有标准格式
        for (pattern in formats) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.ENGLISH)
                // 对于包含Z或时区信息的格式，保持UTC；其他使用用户时区
                if (pattern.contains("'Z'") || pattern.contains("Z") || pattern.contains("z")) {
                    sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                } else {
                    sdf.timeZone = userTimeZone
                }

                val dt = sdf.parse(cleaned)
                if (dt != null) {
                    if (DEBUG) println("✅ 成功解析: $pattern -> ${Date(dt.time)} (时区: ${sdf.timeZone.id})")
                    return dt.time
                }
            } catch (_: Exception) {}
        }

        // 只解析月日（如 "April 5" "Apr 5"），补充年份，设置为当天开始时间
        try {
            val monthDayFormats = listOf("MMMM d", "MMM d")
            for (f in monthDayFormats) {
                try {
                    val sdf = SimpleDateFormat(f, Locale.ENGLISH)
                    sdf.timeZone = userTimeZone
                    val cal = Calendar.getInstance(userTimeZone)
                    val parsed = sdf.parse(cleaned)
                    if (parsed != null) {
                        cal.time = parsed
                        val year = fallbackYear ?: Calendar.getInstance().get(Calendar.YEAR)
                        cal.set(Calendar.YEAR, year)
                        // 设置为当天的开始时间（午夜），而不是当前时间
                        cal.set(Calendar.HOUR_OF_DAY, 0)
                        cal.set(Calendar.MINUTE, 0)
                        cal.set(Calendar.SECOND, 0)
                        cal.set(Calendar.MILLISECOND, 0)
                        if (DEBUG) println("✅ 月日解析: $f -> ${Date(cal.timeInMillis)} (时区: ${cal.timeZone.id})")
                        return cal.timeInMillis
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        // 只解析月日时分（如 "April 5 6:30PM"），补充年份
        try {
            val monthDayTimeFormats = listOf("MMMM d h:mma", "MMM d h:mma", "MMMM d h:mm a", "MMM d h:mm a")
            for (f in monthDayTimeFormats) {
                try {
                    val sdf = SimpleDateFormat(f, Locale.ENGLISH)
                    sdf.timeZone = userTimeZone
                    val cal = Calendar.getInstance(userTimeZone)
                    val parsed = sdf.parse(cleaned)
                    if (parsed != null) {
                        cal.time = parsed
                        val year = fallbackYear ?: Calendar.getInstance().get(Calendar.YEAR)
                        cal.set(Calendar.YEAR, year)
                        if (DEBUG) println("✅ 月日时间解析: $f -> ${Date(cal.timeInMillis)} (时区: ${cal.timeZone.id})")
                        return cal.timeInMillis
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        if (DEBUG) println("❌ 解析失败: '$cleaned'")
        return null
    }

    /**
     * 从文本提取时间信息（原有逻辑）
     */
    private fun extractEventTimeFromText(text: String): ParsedEvents {
        val now = Calendar.getInstance()
        val currentYear = now.get(Calendar.YEAR)
        var exhibition: EventSegment? = null
        val receptions = mutableListOf<EventSegment>()
        val events = mutableListOf<EventSegment>()

        val monthMap = mapOf(
            "jan" to "january", "feb" to "february", "mar" to "march",
            "apr" to "april", "may" to "may", "jun" to "june",
            "jul" to "july", "aug" to "august", "sep" to "september",
            "sept" to "september", "oct" to "october", "nov" to "november", "dec" to "december"
        )

        if (DEBUG) {
            println("=== TimeUtils DEBUG ===")
            println("Original text: $text")
        }

        // ----------- Refactored Pre-processing -----------
        var tempText = text // Start with the original text

        // 1. Key change: Perform case-sensitive replacements FIRST
        // Use original text case to find splits like "pmEvent" -> "pm Event"
        tempText = tempText
            .replace(Regex("([ap]m)([A-Z])"), "$1 $2") // e.g., "8pmClosing" -> "8pm Closing"
            .replace(Regex("(\\d{4})([A-Z])"), "$1 $2") // e.g., "2024Opening" -> "2024 Opening"

        // 2. Now, convert to lowercase for general matching
        tempText = tempText.lowercase(Locale.ENGLISH)

        // 3. Month abbreviation standardization
        monthMap.forEach { (abbr, full) ->
            tempText = tempText.replace(Regex("\\b$abbr\\b"), full)
        }

        // 4. Ordinal number handling (st, nd, rd, th)
        tempText = tempText.replace(Regex("(\\d{1,2})(st|nd|rd|th)\\b"), "$1")

        // 5. New step: Remove leading bullet points
        tempText = tempText.trim().replace(Regex("^[•·*-]\\s*"), "")

        // 6. Standardize whitespace and handle other specific cases
        tempText = tempText
            .replace(Regex("\\s+"), " ")
            .replace("\n", " ")
            .replace("\t", " ")
            .trim()


        if (DEBUG) {
            println("=== Enhanced Pre-processing Result ===")
            println("Processed: $tempText")
        }


        // --------- 块分割递归：支持 "Opening Reception: ... Visit: ... " ---------
        val blocks = Regex("(opening reception:|exhibition dates:|visit:|dates:|viewing hours:|hours:)", RegexOption.IGNORE_CASE)
            .findAll(tempText)
            .map { it.range.first }
            .toList()
            .let { indices ->
                if (indices.isEmpty()) listOf(tempText)
                else indices.plus(tempText.length).zipWithNext().map { (a, b) -> tempText.substring(a, b).trim() }
            }

        if (blocks.size > 1 && blocks.joinToString("").length < tempText.length) {
            if (DEBUG) println("🧩 分块处理: ${blocks.size} 个块")
            blocks.forEach { block ->
                val res = extractEventTimeFromText(block)
                if (res.exhibition != null && exhibition == null) exhibition = res.exhibition
                receptions.addAll(res.receptions)
            }
        }


        // 继续进行标准的文本预处理
        var cleanText = tempText
            .replace(Regex("–|—"), "-")  // 替换长破折号
            // 处理时间范围格式
            .replace(Regex(":\\s*(\\d{1,2})\\s*[-–]\\s*(\\d{1,2})\\s+(am|pm)"), " $1$3-$2$3")  // "November 23: 6 – 8 pm" -> "November 23 6pm-8pm"
            .replace(Regex("(\\d{1,2})\\s*[-–]\\s*(\\d{1,2})\\s+(am|pm)"), "$1$3-$2$3")  // "6 - 8 pm" -> "6pm-8pm"
            .replace(Regex("(\\d{1,2}:\\d{2})\\s*[-–]\\s*(\\d{1,2}:\\d{2})\\s+(am|pm)"), "$1$3-$2$3")  // "6:00 - 9:00 pm"

        if (DEBUG) println("Clean text for matching: $cleanText")

        // 0a: "June 24 | 5-9pm", "April 5•2-4pm"
        val pattern0a = Regex("(\\w+\\s+\\d{1,2})(?:,?\\s*(\\d{4}))?\\s*[|:;•]\\s*(\\d{1,2}(?::\\d{2})?\\s*[ap]m)\\s*[-–]\\s*(\\d{1,2}(?::\\d{2})?\\s*[ap]m)")
        pattern0a.findAll(cleanText).forEach { match ->
            val (dateStr, yearStr, startStr, endStr) = match.destructured
            val year = yearStr.ifBlank { currentYear.toString() }
            val start = "$dateStr $year ${startStr.uppercase()}"
            val end = "$dateStr $year ${endStr.uppercase()}"
            val startMs = parseToMillis(start)
            val endMs = parseToMillis(end)
            if (startMs != null && endMs != null) {
                receptions.add(EventSegment("活动", startMs, endMs))
            }
        }

        // 0b: "June 24 | 5pm"
        val pattern0b = Regex("(\\w+\\s+\\d{1,2})(?:,?\\s*(\\d{4}))?\\s*[|:;•]\\s*(\\d{1,2}(?::\\d{2})?\\s*[ap]m)")
        pattern0b.findAll(cleanText).forEach { match ->
            val (dateStr, yearStr, timeStr) = match.destructured
            val year = yearStr.ifBlank { currentYear.toString() }
            val start = "$dateStr $year ${timeStr.uppercase()}"
            val startMs = parseToMillis(start)
            if (startMs != null) {
                receptions.add(EventSegment("活动", startMs, startMs + 2 * 60 * 60 * 1000))
            }
        }

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

        // 更多模式匹配...
        addMorePatternMatches(cleanText, currentYear, events)

        // 新增：处理特殊格式和多行内容
        addSpecialFormatPatterns(cleanText, currentYear, events, receptions)

        // 合并所有收集到的event、receptions、exhibition
        val exhibitionEvent = exhibition ?: events.find { it.name == "展览" }
        val receptionEvents = (receptions + events.filter { it.name != "展览" }).distinctBy { "${it.name}_${it.start}_${it.end}" }

        return ParsedEvents(exhibitionEvent, receptionEvents)
    }

    /**
     * 处理特殊格式和多行内容
     */
    private fun addSpecialFormatPatterns(text: String, currentYear: Int, events: MutableList<EventSegment>, receptions: MutableList<EventSegment>) {
        // 1. 处理 "Jul 17, 2025 at 7:30 pm" 格式 (已移至 addMorePatternMatches)

        // 2. 增强：处理连续的时间信息（如 "Doors 7:30p Program 8:00p"）
        // 2a. 标准格式
        Regex("doors\\s+(\\d{1,2}:\\d{2})\\s*([ap]?)m?\\s+program\\s+(\\d{1,2}:\\d{2})\\s*([ap]?)m?", RegexOption.IGNORE_CASE).findAll(text).forEach { match ->
            val (doorsTime, doorsAmPm, programTime, programAmPm) = match.destructured
            val doorsAmPmFinal = if (doorsAmPm.isNotBlank()) doorsAmPm else "p"
            val programAmPmFinal = if (programAmPm.isNotBlank()) programAmPm else "p"

            val cal = Calendar.getInstance()
            val doorsMs = parseToMillis("${cal.get(Calendar.MONTH) + 1} ${cal.get(Calendar.DAY_OF_MONTH)} ${cal.get(Calendar.YEAR)} ${doorsTime}${doorsAmPmFinal}m")
            val programMs = parseToMillis("${cal.get(Calendar.MONTH) + 1} ${cal.get(Calendar.DAY_OF_MONTH)} ${cal.get(Calendar.YEAR)} ${programTime}${programAmPmFinal}m")

            if (doorsMs != null) events.add(EventSegment("开门", doorsMs, doorsMs + 30 * 60 * 1000))
            if (programMs != null) events.add(EventSegment("节目开始", programMs, programMs + 2 * 60 * 60 * 1000))
            if (DEBUG) println("✅ 增强Doors/Program格式找到: $doorsTime${doorsAmPmFinal}m -> $programTime${programAmPmFinal}m")
        }

        // 2b. 缩写格式 "7:30p"
        Regex("doors\\s+(\\d{1,2}:\\d{2})p\\s+program\\s+(\\d{1,2}:\\d{2})p", RegexOption.IGNORE_CASE).findAll(text).forEach { match ->
            val (doorsTime, programTime) = match.destructured
            val cal = Calendar.getInstance()
            val doorsMs = parseToMillis("${cal.get(Calendar.MONTH) + 1} ${cal.get(Calendar.DAY_OF_MONTH)} ${cal.get(Calendar.YEAR)} ${doorsTime}PM")
            val programMs = parseToMillis("${cal.get(Calendar.MONTH) + 1} ${cal.get(Calendar.DAY_OF_MONTH)} ${cal.get(Calendar.YEAR)} ${programTime}PM")

            if (doorsMs != null) events.add(EventSegment("开门", doorsMs, doorsMs + 30 * 60 * 1000))
            if (programMs != null) events.add(EventSegment("节目开始", programMs, programMs + 2 * 60 * 60 * 1000))
            if (DEBUG) println("✅ 缩写Doors/Program格式找到: $doorsTime -> $programTime")
        }

        // 3. 处理极端粘连格式：活动名紧贴时间
        // 3a. 查找所有可能的时间，然后向前搜索活动名
        val timePattern = Regex("(\\d{1,2}(?::\\d{2})?\\s*[ap]m)", RegexOption.IGNORE_CASE)
        val timeMatches = timePattern.findAll(text).toList()

        for (timeMatch in timeMatches) {
            val timeStr = timeMatch.value.trim()
            val startPos = timeMatch.range.first

            // 向前查找可能的活动名（最多50个字符）
            val beforeText = text.substring(maxOf(0, startPos - 50), startPos)

            // 查找活动关键词
            val activityKeywords = listOf(
                "feeding tomorrow", "book launch", "conversation", "screening",
                "performance", "open room", "co-presented", "doors", "program", "closing reception", "artist talk"
            )

            for (keyword in activityKeywords) {
                if (beforeText.lowercase().contains(keyword)) {
                    val cal = Calendar.getInstance()
                    val eventMs = parseToMillis("${cal.get(Calendar.MONTH) + 1} ${cal.get(Calendar.DAY_OF_MONTH)} ${cal.get(Calendar.YEAR)} $timeStr")
                    if (eventMs != null) {
                        events.add(EventSegment(keyword, eventMs, eventMs + 2 * 60 * 60 * 1000))
                        if (DEBUG) println("✅ 粘连格式找到: $keyword -> $timeStr")
                    }
                    break
                }
            }
        }

        // 4. 处理bullet点多行格式
        val bulletLines = text.split('\n').map { it.trim() }
        var currentDate: String? = null
        var currentYearStr: String? = null

        for (line in bulletLines) {
            val cleanLine = line.replace(Regex("^[•·*-]\\s*"), "").trim()

            // 检查是否包含日期
            val dateMatch = Regex("(\\w+)\\s+(\\d{1,2}),?\\s*(\\d{4})", RegexOption.IGNORE_CASE).find(cleanLine)
            if (dateMatch != null) {
                val (month, day, year) = dateMatch.destructured
                currentDate = "$month $day"
                currentYearStr = year
                if (DEBUG) println("📅 找到日期行: $currentDate, $currentYearStr")
                continue
            }

            // 检查是否只包含年份
            val yearMatch = Regex("^(\\d{4})$").find(cleanLine)
            if (yearMatch != null) {
                currentYearStr = yearMatch.value
                if (DEBUG) println("📅 找到年份行: $currentYearStr")
                continue
            }

            // 检查是否包含时间信息
            val timeMatch = Regex("(doors?|movie starts?|program|starts?)\\s*:?\\s*(\\d{1,2}(?::\\d{2})?\\s*[ap]?m?)", RegexOption.IGNORE_CASE).find(cleanLine)
            if (timeMatch != null && currentDate != null && currentYearStr != null) {
                val (eventType, timeStr) = timeMatch.destructured
                val fullTimeStr = if (timeStr.matches(Regex("\\d{1,2}$"))) "${timeStr}pm" else timeStr
                val dateTime = "$currentDate $currentYearStr $fullTimeStr"
                val startMs = parseToMillis(dateTime)
                if (startMs != null) {
                    events.add(EventSegment(eventType, startMs, startMs + 2 * 60 * 60 * 1000))
                    if (DEBUG) println("✅ Bullet格式找到: $eventType -> $dateTime")
                }
            }

            // 检查简单的时间格式（如 "Doors 7:00pm"）
            val simpleTimeMatch = Regex("(doors?|program)\\s+(\\d{1,2}(?::\\d{2})?\\s*[ap]m)", RegexOption.IGNORE_CASE).find(cleanLine)
            if (simpleTimeMatch != null && currentDate != null && currentYearStr != null) {
                val (eventType, timeStr) = simpleTimeMatch.destructured
                val dateTime = "$currentDate $currentYearStr $timeStr"
                val startMs = parseToMillis(dateTime)
                if (startMs != null) {
                    events.add(EventSegment(eventType, startMs, startMs + 2 * 60 * 60 * 1000))
                    if (DEBUG) println("✅ 简单时间格式找到: $eventType -> $dateTime")
                }
            }
        }

        // 5. 处理 "Exhibition Dates:" 后面紧跟日期的格式
        Regex("exhibition dates:\\s*(\\w+)\\s+(\\d{1,2})\\s*[-–]\\s*(\\d{1,2}),?\\s*(\\d{4})", RegexOption.IGNORE_CASE).findAll(text).forEach { match ->
            val (month, startDay, endDay, year) = match.destructured
            val startMs = parseToMillis("$month $startDay $year")
            val endMs = parseToMillis("$month $endDay $year")
            if (startMs != null && endMs != null) {
                events.add(EventSegment("展览", startMs, endMs))
                if (DEBUG) println("✅ Exhibition Dates格式找到: $month $startDay-$endDay $year")
            }
        }

        // 6. 处理没有逗号的时间范围 "Saturday, October 5 6–8pm"
        Regex("(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday),?\\s*(\\w+\\s+\\d{1,2})\\s+(\\d{1,2})[-–](\\d{1,2})\\s*([ap]m)", RegexOption.IGNORE_CASE).findAll(text).forEach { match ->
            val (date, startHour, endHour, ampm) = match.destructured
            val year = currentYear.toString()
            val startMs = parseToMillis("$date $year $startHour${ampm.uppercase()}")
            val endMs = parseToMillis("$date $year $endHour${ampm.uppercase()}")
            if (startMs != null && endMs != null) {
                receptions.add(EventSegment("活动", startMs, endMs))
                if (DEBUG) println("✅ 星期+时间范围找到: $date $startHour-$endHour$ampm")
            }
        }

        // 7. 处理 "ON VIEW" 格式
        Regex("on view\\s*(\\w+\\s+\\d{1,2})[-–](\\w+\\s+\\d{1,2}),?\\s*(\\d{4})", RegexOption.IGNORE_CASE).findAll(text).forEach { match ->
            val (startDate, endDate, year) = match.destructured
            val startMs = parseToMillis("$startDate $year")
            val endMs = parseToMillis("$endDate $year")
            if (startMs != null && endMs != null) {
                events.add(EventSegment("展览", startMs, endMs))
                if (DEBUG) println("✅ ON VIEW格式找到: $startDate - $endDate $year")
            }
        }

        // 8. 新增：处理复杂的粘连文本 "pmActivityName" 形式
        Regex("(\\d{1,2}(?::\\d{2})?\\s*[ap]m)([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)*)", RegexOption.IGNORE_CASE).findAll(text).forEach { match ->
            val (timeStr, activityName) = match.destructured
            val cal = Calendar.getInstance()
            val eventMs = parseToMillis("${cal.get(Calendar.MONTH) + 1} ${cal.get(Calendar.DAY_OF_MONTH)} ${cal.get(Calendar.YEAR)} $timeStr")
            if (eventMs != null) {
                events.add(EventSegment(activityName, eventMs, eventMs + 2 * 60 * 60 * 1000))
                if (DEBUG) println("✅ 粘连活动格式找到: $timeStr$activityName")
            }
        }
    }
    private fun addMorePatternMatches(cleanText: String, currentYear: Int, events: MutableList<EventSegment>) {
        // 1a: 带星期、有年份的具体时间 "saturday, february 1, 2025, 6pm-8pm"
        Regex("(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|wed|thu|fri|sat|sun),?\\s*(\\w+\\s+\\d{1,2}),?\\s*(\\d{4}),?\\s*(?:at\\s+)?(\\d{1,2}(?::\\d{2})?\\s*[ap]m)\\s*(?:to|[-–])\\s*(\\d{1,2}(?::\\d{2})?\\s*[ap]m)").findAll(cleanText).forEach { match ->
            val (dateStr, yearStr, startStr, endStr) = match.destructured
            val start = "$dateStr $yearStr ${startStr.uppercase()}"
            val end = "$dateStr $yearStr ${endStr.uppercase()}"
            val startMs = parseToMillis(start)
            val endMs = parseToMillis(end)
            if (startMs != null && endMs != null) events.add(EventSegment("活动", startMs, endMs))
        }

        // 1b: 带星期、无年份
        Regex("(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|wed|thu|fri|sat|sun),?\\s*(\\w+\\s+\\d{1,2}),?\\s*(?:at\\s+)?(\\d{1,2}(?::\\d{2})?\\s*[ap]m)\\s*(?:to|[-–])\\s*(\\d{1,2}(?::\\d{2})?\\s*[ap]m)").findAll(cleanText).forEach { match ->
            val (dateStr, startStr, endStr) = match.destructured
            val start = "$dateStr $currentYear ${startStr.uppercase()}"
            val end = "$dateStr $currentYear ${endStr.uppercase()}"
            val startMs = parseToMillis(start)
            val endMs = parseToMillis(end)
            if (startMs != null && endMs != null) events.add(EventSegment("活动", startMs, endMs))
        }

        // 1c: 不带星期、有年份
        Regex("(\\w+\\s+\\d{1,2}),?\\s*(\\d{4}),?\\s*(?:at\\s+)?(\\d{1,2}(?::\\d{2})?\\s*[ap]m)\\s*(?:to|[-–])\\s*(\\d{1,2}(?::\\d{2})?\\s*[ap]m)").findAll(cleanText).forEach { match ->
            val (dateStr, yearStr, startStr, endStr) = match.destructured
            val start = "$dateStr $yearStr ${startStr.uppercase()}"
            val end = "$dateStr $yearStr ${endStr.uppercase()}"
            val startMs = parseToMillis(start)
            val endMs = parseToMillis(end)
            if (startMs != null && endMs != null) events.add(EventSegment("活动", startMs, endMs))
        }

        // 1d: 不带星期、无年份
        Regex("(\\w+\\s+\\d{1,2}),?\\s*(?:at\\s+)?(\\d{1,2}(?::\\d{2})?\\s*[ap]m)\\s*(?:to|[-–])\\s*(\\d{1,2}(?::\\d{2})?\\s*[ap]m)").findAll(cleanText).forEach { match ->
            val (dateStr, startStr, endStr) = match.destructured
            val start = "$dateStr $currentYear ${startStr.uppercase()}"
            val end = "$dateStr $currentYear ${endStr.uppercase()}"
            val startMs = parseToMillis(start)
            val endMs = parseToMillis(end)
            if (startMs != null && endMs != null) events.add(EventSegment("活动", startMs, endMs))
        }

        // 2a: "from february 1 to february 23"
        Regex("from\\s+(\\w+\\s+\\d{1,2})(?:,?\\s*(\\d{4}))?\\s+(?:to|[-–])\\s+(\\w+\\s+\\d{1,2})(?:,?\\s*(\\d{4}))?").findAll(cleanText).forEach { match ->
            val (startDate, startYear, endDate, endYear) = match.destructured
            val sy = startYear.ifBlank { currentYear.toString() }
            val ey = if (endYear.isNotBlank()) endYear else sy
            val startMs = parseToMillis("$startDate $sy")
            val endMs = parseToMillis("$endDate $ey")
            if (startMs != null && endMs != null) events.add(EventSegment("展览", startMs, endMs))
        }

        // 2b: "november 22, 2024 – january 5, 2025"
        Regex("(\\w+\\s+\\d{1,2}),?\\s*(\\d{4})\\s*[-–]\\s*(\\w+\\s+\\d{1,2}),?\\s*(\\d{4})").findAll(cleanText).forEach { match ->
            val (startDate, startYear, endDate, endYear) = match.destructured
            val startMs = parseToMillis("$startDate $startYear")
            val endMs = parseToMillis("$endDate $endYear")
            if (startMs != null && endMs != null) events.add(EventSegment("展览", startMs, endMs))
        }

        // 2c: "february 1-23, 2025"
        Regex("(\\w+)\\s+(\\d{1,2})[-–](\\d{1,2}),?\\s*(\\d{4})").findAll(cleanText).forEach { match ->
            val (month, startDay, endDay, year) = match.destructured
            val startMs = parseToMillis("$month $startDay $year")
            val endMs = parseToMillis("$month $endDay $year")
            if (startMs != null && endMs != null) events.add(EventSegment("展览", startMs, endMs))
        }

        // 2d: "october 5–november 10, 2024"
        Regex("(\\w+\\s+\\d{1,2})[-–](\\w+\\s+\\d{1,2}),?\\s*(\\d{4})").findAll(cleanText).forEach { match ->
            val (startDate, endDate, year) = match.destructured
            val startMs = parseToMillis("$startDate $year")
            val endMs = parseToMillis("$endDate $year")
            if (startMs != null && endMs != null) events.add(EventSegment("展览", startMs, endMs))
        }

        // 3: 提交截止时间 "thursday, october 31, 2024 at 11:59 pm est"
        Regex("(?:deadline|due):\\s*(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|wed|thu|fri|sat|sun),?\\s*(\\w+\\s+\\d{1,2}),?\\s*(\\d{4})\\s*(?:at\\s+)?(\\d{1,2}:\\d{2})\\s*([ap]m)").findAll(cleanText).forEach { match ->
            val (date, year, time, ampm) = match.destructured
            val dateTime = "$date $year ${time.uppercase()}${ampm.uppercase()}"
            val startMs = parseToMillis(dateTime)
            if (startMs != null) events.add(EventSegment("截止时间", startMs, startMs))
        }

        // 4a: "saturday, april 5 at 6:30pm"
        Regex("(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|wed|thu|fri|sat|sun),?\\s*(\\w+\\s+\\d{1,2})(?:,?\\s*(\\d{4}))?\\s*,?\\s*(?:at\\s+)?(\\d{1,2}(?::\\d{2})?\\s*[ap]m)").findAll(cleanText).forEach { match ->
            val (date, year, time) = match.destructured
            val y = year.ifBlank { currentYear.toString() }
            val start = "$date $y ${time.uppercase()}"
            val startMs = parseToMillis(start)
            if (startMs != null) events.add(EventSegment("活动", startMs, startMs + 2 * 60 * 60 * 1000))
        }

        // 4b: "april 5 at 6:30pm"
        Regex("(\\w+\\s+\\d{1,2})(?:,?\\s*(\\d{4}))?\\s*,?\\s*(?:at\\s+)?(\\d{1,2}(?::\\d{2})?\\s*[ap]m)").findAll(cleanText).forEach { match ->
            val (date, year, time) = match.destructured
            val y = year.ifBlank { currentYear.toString() }
            val start = "$date $y ${time.uppercase()}"
            val startMs = parseToMillis(start)
            if (startMs != null) events.add(EventSegment("活动", startMs, startMs + 2 * 60 * 60 * 1000))
        }

        // 4c: NEW - "Month Day, Year at H:MM am/pm"
        Regex("(\\w+\\s+\\d{1,2},?\\s*\\d{4})\\s+at\\s+(\\d{1,2}:\\d{2}\\s*[ap]m)").findAll(cleanText).forEach { match ->
            val (dateStr, timeStr) = match.destructured
            val startMs = parseToMillis("$dateStr ${timeStr.uppercase()}")
            if (startMs != null) {
                events.add(EventSegment("活动", startMs, startMs + 2 * 60 * 60 * 1000)) // Default 2-hour duration
                if (DEBUG) println("✅ Found event with 'at' format: $dateStr $timeStr")
            }
        }

        // 0c: 带星期 "saturday, june 24 | 5-9pm"
        val pattern0c = Regex("(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|wed|thu|fri|sat|sun),?\\s*(\\w+\\s+\\d{1,2})(?:,?\\s*(\\d{4}))?\\s*[|:;•]\\s*(\\d{1,2}(?::\\d{2})?\\s*[ap]m)\\s*[-–]\\s*(\\d{1,2}(?::\\d{2})?\\s*[ap]m)")
        pattern0c.findAll(cleanText).forEach { match ->
            val (dateStr, yearStr, startStr, endStr) = match.destructured
            val year = yearStr.ifBlank { currentYear.toString() }
            val start = "$dateStr $year ${startStr.uppercase()}"
            val end = "$dateStr $year ${endStr.uppercase()}"
            val startMs = parseToMillis(start)
            val endMs = parseToMillis(end)
            if (startMs != null && endMs != null) {
                events.add(EventSegment("活动", startMs, endMs))
            }
        }

        // 0d: "Open Room•May 3, 2025•5pm"
        val pattern0d = Regex("\\w+\\s+\\w+\\s*[|:;•]\\s*(\\w+\\s+\\d{1,2})(?:,?\\s*(\\d{4}))?\\s*[|:;•]\\s*(\\d{1,2}(?::\\d{2})?\\s*[ap]m)")
        pattern0d.findAll(cleanText).forEach { match ->
            val (dateStr, yearStr, timeStr) = match.destructured
            val year = yearStr.ifBlank { currentYear.toString() }
            val start = "$dateStr $year ${timeStr.uppercase()}"
            val startMs = parseToMillis(start)
            if (startMs != null) {
                events.add(EventSegment("活动", startMs, startMs + 2 * 60 * 60 * 1000))
            }
        }

        // 额外模式：处理更多边缘情况

        // 5: "Every Saturday from 2-5pm"
        Regex("every\\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\s+from\\s+(\\d{1,2}(?::\\d{2})?\\s*[ap]m)\\s*[-–]\\s*(\\d{1,2}(?::\\d{2})?\\s*[ap]m)").findAll(cleanText).forEach { match ->
            val (dayOfWeek, startTime, endTime) = match.destructured
            // 这里可以创建重复事件，暂时创建一个示例事件
            val cal = Calendar.getInstance()
            val startMs = parseToMillis("${cal.get(Calendar.MONTH) + 1} ${cal.get(Calendar.DAY_OF_MONTH)} ${cal.get(Calendar.YEAR)} ${startTime.uppercase()}")
            val endMs = parseToMillis("${cal.get(Calendar.MONTH) + 1} ${cal.get(Calendar.DAY_OF_MONTH)} ${cal.get(Calendar.YEAR)} ${endTime.uppercase()}")
            if (startMs != null && endMs != null) {
                events.add(EventSegment("定期活动", startMs, endMs))
            }
        }

        // 6: "Weekends only: 1-6pm"
        Regex("weekends?\\s+only:\\s*(\\d{1,2}(?::\\d{2})?\\s*[ap]m)\\s*[-–]\\s*(\\d{1,2}(?::\\d{2})?\\s*[ap]m)").findAll(cleanText).forEach { match ->
            val (startTime, endTime) = match.destructured
            val cal = Calendar.getInstance()
            val startMs = parseToMillis("${cal.get(Calendar.MONTH) + 1} ${cal.get(Calendar.DAY_OF_MONTH)} ${cal.get(Calendar.YEAR)} ${startTime.uppercase()}")
            val endMs = parseToMillis("${cal.get(Calendar.MONTH) + 1} ${cal.get(Calendar.DAY_OF_MONTH)} ${cal.get(Calendar.YEAR)} ${endTime.uppercase()}")
            if (startMs != null && endMs != null) {
                events.add(EventSegment("周末开放", startMs, endMs))
            }
        }

        // 7: "Daily 10am-6pm except Mondays"
        Regex("daily\\s+(\\d{1,2}(?::\\d{2})?\\s*[ap]m)\\s*[-–]\\s*(\\d{1,2}(?::\\d{2})?\\s*[ap]m)").findAll(cleanText).forEach { match ->
            val (startTime, endTime) = match.destructured
            val cal = Calendar.getInstance()
            val startMs = parseToMillis("${cal.get(Calendar.MONTH) + 1} ${cal.get(Calendar.DAY_OF_MONTH)} ${cal.get(Calendar.YEAR)} ${startTime.uppercase()}")
            val endMs = parseToMillis("${cal.get(Calendar.MONTH) + 1} ${cal.get(Calendar.DAY_OF_MONTH)} ${cal.get(Calendar.YEAR)} ${endTime.uppercase()}")
            if (startMs != null && endMs != null) {
                events.add(EventSegment("每日开放", startMs, endMs))
            }
        }
    }

    /**
     * 从网页抓取时间信息
     */
    private suspend fun extractFromWebPage(url: String): ParsedEvents = withContext(Dispatchers.IO) {
        // 检查缓存
        if (scrapingConfig.cacheEnabled) {
            webCache[url]?.let { cached ->
                if (DEBUG) println("📋 使用缓存结果: $url")
                return@withContext cached
            }
        }

        try {
            // 抓取网页内容
            val doc = Jsoup.connect(url)
                .userAgent(scrapingConfig.userAgent)
                .timeout(scrapingConfig.timeout)
                .get()

            val result = extractTimeFromDocument(doc, url)

            // 缓存结果
            if (scrapingConfig.cacheEnabled && !result.isEmpty()) {
                webCache[url] = result
            }

            result
        } catch (e: Exception) {
            if (DEBUG) println("❌ 网页抓取失败: $url - ${e.message}")
            ParsedEvents(null, emptyList())
        }
    }

    /**
     * 从HTML文档中提取时间信息
     */
    private fun extractTimeFromDocument(doc: Document, url: String): ParsedEvents {
        if (DEBUG) println("🔍 分析网页: $url")

        // 收集所有可能包含时间信息的文本
        val timeTexts = mutableSetOf<String>()

        // 1. 查找结构化数据 (JSON-LD, Microdata)
        extractStructuredData(doc, timeTexts)

        // 2. 查找特定的HTML元素
        extractFromSpecificElements(doc, timeTexts)

        // 3. 查找包含时间关键词的文本
        extractFromTimeKeywords(doc, timeTexts)

        // 4. 特殊站点处理
        extractFromSpecificSites(doc, url, timeTexts)

        // 尝试解析所有找到的文本
        return parseTimeTexts(timeTexts)
    }

    /**
     * 提取结构化数据 (JSON-LD, Schema.org等)
     */
    private fun extractStructuredData(doc: Document, timeTexts: MutableSet<String>) {
        // JSON-LD 结构化数据
        doc.select("script[type=application/ld+json]").forEach { script ->
            val jsonText = script.html()
            if (jsonText.contains("Event") || jsonText.contains("startDate") || jsonText.contains("endDate")) {
                timeTexts.add(jsonText)
                if (DEBUG) println("📅 找到JSON-LD数据")
            }
        }

        // Microdata
        doc.select("[itemtype*=Event], [itemprop*=startDate], [itemprop*=endDate], [itemprop*=doorTime]").forEach { element ->
            timeTexts.add(element.text())
            if (DEBUG) println("📅 找到Microdata: ${element.text()}")
        }
    }

    /**
     * 从特定HTML元素提取
     */
    private fun extractFromSpecificElements(doc: Document, timeTexts: MutableSet<String>) {
        // 时间相关的CSS类名和ID
        val timeSelectors = listOf(
            ".date", ".time", ".datetime", ".event-time", ".event-date",
            ".screening-time", ".show-time", ".performance-time", ".screening",
            "#date", "#time", "#datetime", "#event-time",
            "[class*=time]", "[class*=date]", "[class*=screening]", "[id*=time]", "[id*=date]"
        )

        timeSelectors.forEach { selector ->
            doc.select(selector).forEach { element ->
                val text = element.text().trim()
                if (text.isNotBlank() && containsTimePattern(text)) {
                    timeTexts.add(text)
                    if (DEBUG) println("🕐 CSS选择器找到: $selector -> $text")
                }
            }
        }

        // 查找特定标签
        doc.select("time").forEach { element ->
            val datetime = element.attr("datetime")
            val text = element.text()
            if (datetime.isNotBlank()) timeTexts.add(datetime)
            if (text.isNotBlank()) timeTexts.add(text)
        }
    }

    /**
     * 基于关键词查找包含时间的文本
     */
    private fun extractFromTimeKeywords(doc: Document, timeTexts: MutableSet<String>) {
        val timeKeywords = listOf(
            "screening", "performance", "show", "event", "opening", "reception",
            "exhibition", "dates", "visit", "hours", "schedule", "calendar"
        )

        // 查找包含时间关键词的段落
        doc.select("p, div, span, h1, h2, h3, h4, h5, h6").forEach { element ->
            val text = element.text().lowercase()

            if (timeKeywords.any { keyword -> text.contains(keyword) } &&
                containsTimePattern(text)) {
                timeTexts.add(element.text())
                if (DEBUG) println("🔤 关键词匹配: ${element.text()}")
            }
        }

        // 查找包含时间格式的文本
        doc.allElements.forEach { element ->
            if (element.children().isEmpty()) { // 只处理叶子节点
                val text = element.text()
                if (containsTimePattern(text)) {
                    timeTexts.add(text)
                }
            }
        }
    }

    /**
     * 特定网站的特殊处理
     */
    private fun extractFromSpecificSites(doc: Document, url: String, timeTexts: MutableSet<String>) {
        val domain = try { URL(url).host } catch (e: Exception) { "" }

        when {
            // UPDATED: Performance Space New York
            domain.contains("performancespacenewyork") -> {
                doc.select(".sqs-block-content p").forEach {
                    val text = it.text()
                    if (containsTimePattern(text)) {
                        timeTexts.add(text)
                        if (DEBUG) println("📅 Found text on PerformanceSpaceNewYork: $text")
                    }
                }
            }

            // Anthology Film Archives
            domain.contains("anthologyfilmarchives") -> {
                doc.select(".event-info, .screening-details, .film-info").forEach {
                    timeTexts.add(it.text())
                }
            }

            // 通用的艺术/文化网站模式
            domain.contains("gallery") || domain.contains("museum") || domain.contains("theater") -> {
                doc.select(".event, .exhibition, .show, .screening").forEach {
                    timeTexts.add(it.text())
                }
            }
        }
    }

    /**
     * 检查文本是否包含时间模式
     */
    private fun containsTimePattern(text: String): Boolean {
        val timePatterns = listOf(
            Regex("\\d{1,2}:\\d{2}\\s*[ap]m", RegexOption.IGNORE_CASE),
            Regex("\\d{1,2}\\s*[ap]m", RegexOption.IGNORE_CASE),
            Regex("\\d{1,2}\\s*[-–|]\\s*\\d{1,2}\\s*[ap]m", RegexOption.IGNORE_CASE),
            Regex("(january|february|march|april|may|june|july|august|september|october|november|december)\\s+\\d{1,2}", RegexOption.IGNORE_CASE),
            Regex("(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\\s+\\d{1,2}", RegexOption.IGNORE_CASE),
            Regex("\\d{1,2}\\s+(january|february|march|april|may|june|july|august|september|october|november|december)", RegexOption.IGNORE_CASE),
            Regex("\\d{4}-\\d{2}-\\d{2}"),
            Regex("\\d{1,2}/\\d{1,2}/\\d{4}")
        )

        return timePatterns.any { it.find(text.lowercase()) != null }
    }


    /**
     * 解析所有收集到的时间文本
     */
    private fun parseTimeTexts(timeTexts: Set<String>): ParsedEvents {
        if (DEBUG) println("🔍 开始解析 ${timeTexts.size} 个时间文本")

        val allResults = mutableListOf<ParsedEvents>()

        // 将所有文本合并为一个进行解析，以更好地处理跨元素的信息
        val combinedText = timeTexts.joinToString(separator = "\n")

        if (DEBUG) println("Combined text for parsing:\n$combinedText")

        val result = extractEventTimeFromText(combinedText)
        if (!result.isEmpty()) {
            allResults.add(result)
            if (DEBUG) println("✅ 成功解析合并后的文本")
        }


        // 合并结果
        return mergeResults(allResults)
    }

    /**
     * 合并多个解析结果
     */
    private fun mergeResults(results: List<ParsedEvents>): ParsedEvents {
        if (results.isEmpty()) return ParsedEvents(null, emptyList())
        if (results.size == 1) return results.first()

        // 选择最完整的exhibition
        val bestExhibition = results.mapNotNull { it.exhibition }
            .maxByOrNull { (it.start ?: 0) + (it.end ?: 0) }

        // 合并所有receptions
        val allReceptions = results.flatMap { it.receptions }.distinctBy {
            "${it.name}_${it.start}_${it.end}"
        }

        return ParsedEvents(bestExhibition, allReceptions)
    }

    /**
     * 配置网页抓取参数
     */
    fun configureWebScraping(config: WebScrapingConfig) {
        scrapingConfig = config
    }

    /**
     * 清除网页抓取缓存
     */
    fun clearWebCache() {
        webCache.clear()
    }

    // 测试函数
    fun testTimeExtraction() {
        val testCases = listOf(
            // 用户提供的最新测试用例
            "Jul 13, 2025 at 7:30 pm",
            "•June 24 | 5-9pm",
            "September 7 – 29, 2024Opening Reception: Saturday, September 7, 6-8pmClosing Reception and Artist Talk: Sunday September 29, 4pm",
            "Opening Reception: Saturday, March 08, 2025, 6:00 – 8:00 pmVisit: March 08 – 30, 2025, Friday – Sunday 1:00 – 6 pm. Other days by appointment.",
            "Opening Reception: Saturday, June 7th, 2025, 6:00 – 9:00 pmDates: June 07 – 29, 2025, Thursday – Sunday 1:00 – 6 pm. Other days by appointment.",
            "Opens on July 12, 2024 at the PS 122 Gallery,On view through July 28, 2024.Opening reception: July 12, 6:00-8:00pm",

            // 分隔符格式测试
            "June 24 | 5-9pm",
            "June 24 | 5pm",
            "February 15 | 3pm",  // 新增：针对用户具体案例
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
            println("\n\n" + "=".repeat(60))
            println("▶️ 测试案例 ${index + 1}: $testCase")
            println("=".repeat(60))
            val result = extractEventTimeFromText(testCase)
            if (!result.isEmpty()) {
                println("✅ 解析成功:\n${result.toReadableString()}")
            } else {
                println("❌ 解析失败")
            }
            println("-".repeat(60))
        }
    }
}
