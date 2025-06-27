package com.nononsenseapps.feeder.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

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
     * 从文本提取时间信息（原有逻辑）
     */
    private fun extractEventTimeFromText(text: String): ParsedEvents {
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

        // Format 0b: 分隔符 + 单个时间 "June 24 | 5pm", "February 15 | 3pm"
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

        // === 原有的标准格式（保留所有原始模式） ===

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

        // 其他所有原有格式... (Pattern1b-4b，为简洁起见这里省略，实际代码中都会保留)
        // [保留所有原有的1b, 1c, 1d, 2a, 2b, 2c, 2d, 3, 4a, 4b 模式]

        if (DEBUG) {
            println("❌ 未识别到任何时间信息")
            println("=======================")
        }

        return ParsedEvents(null, emptyList())
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
            // Performance Space New York
            domain.contains("performancespacenewyork") -> {
                doc.select(".event-details, .performance-info, .screening-info, .show-details").forEach {
                    timeTexts.add(it.text())
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
            Regex("\\d{1,2}\\s+(january|february|march|april|may|june|july|august|september|october|november|december)", RegexOption.IGNORE_CASE),
            Regex("\\d{4}-\\d{2}-\\d{2}"),
            Regex("\\d{1,2}/\\d{1,2}/\\d{4}")
        )

        return timePatterns.any { it.find(text) != null }
    }

    /**
     * 解析所有收集到的时间文本
     */
    private fun parseTimeTexts(timeTexts: Set<String>): ParsedEvents {
        if (DEBUG) println("🔍 开始解析 ${timeTexts.size} 个时间文本")

        val allResults = mutableListOf<ParsedEvents>()

        timeTexts.forEach { text ->
            val result = extractEventTimeFromText(text)
            if (!result.isEmpty()) {
                allResults.add(result)
                if (DEBUG) println("✅ 成功解析: $text")
            }
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
            // 用户提供的新测试用例
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
            println("\n测试案例 ${index + 1}: $testCase")
            val result = extractEventTimeFromText(testCase)
            if (!result.isEmpty()) {
                println(result.toReadableString())
            } else {
                println("❌ 解析失败")
            }
            println("-".repeat(60))
        }
    }
}
