package com.nononsenseapps.feeder.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit

/**
 * Sitemap解析工具类
 * 用于从网站sitemap.xml中提取URL和时间信息，作为RSS源的备选方案
 */
class SitemapParser {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Sitemap中的URL条目
     */
    data class SitemapEntry(
        val url: String,
        val lastModified: LocalDateTime?,
        val changeFreq: String? = null,
        val priority: Double? = null
    )

    /**
     * 解析结果
     */
    data class SitemapResult(
        val entries: List<SitemapEntry>,
        val success: Boolean,
        val errorMessage: String? = null
    )

    /**
     * 网页内容提取结果
     */
    data class PageContent(
        val title: String,
        val description: String,
        val publishDate: LocalDateTime?,
        val fullContent: String,        // 完整HTML内容
        val plainText: String,          // 纯文本内容
        val imageUrls: List<String>,    // 所有图片URL
        val featuredImage: String? = null
    )

    /**
     * 常见的sitemap路径
     */
    private val commonSitemapPaths = listOf(
        "/sitemap.xml",
        "/sitemap_index.xml",
        "/sitemaps.xml",
        "/sitemap/sitemap.xml",
        "/wp-sitemap.xml"  // WordPress默认
    )

    /**
     * 支持的日期格式
     */
    private val dateFormatters = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE_TIME,
        DateTimeFormatter.ISO_OFFSET_DATE_TIME,
        DateTimeFormatter.ISO_INSTANT,
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd")
    )

    /**
     * 从网站域名解析sitemap
     */
    suspend fun parseSitemap(baseUrl: String): SitemapResult {
        return withContext(Dispatchers.IO) {
            try {
                val normalizedUrl = normalizeBaseUrl(baseUrl)

                // 尝试多个可能的sitemap路径
                for (path in commonSitemapPaths) {
                    val sitemapUrl = normalizedUrl + path
                    val result = tryParseSitemapUrl(sitemapUrl)
                    if (result.success) {
                        return@withContext result
                    }
                }

                SitemapResult(
                    entries = emptyList(),
                    success = false,
                    errorMessage = "未找到有效的sitemap.xml文件"
                )

            } catch (e: Exception) {
                SitemapResult(
                    entries = emptyList(),
                    success = false,
                    errorMessage = "解析sitemap时发生错误: ${e.message}"
                )
            }
        }
    }

    /**
     * 直接解析指定的sitemap URL
     */
    suspend fun parseSitemapUrl(sitemapUrl: String): SitemapResult {
        return withContext(Dispatchers.IO) {
            tryParseSitemapUrl(sitemapUrl)
        }
    }

    private fun tryParseSitemapUrl(sitemapUrl: String): SitemapResult {
        return try {
            val request = Request.Builder()
                .url(sitemapUrl)
                .header("User-Agent", "Mozilla/5.0 (compatible; FeederBot/1.0)")
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                return SitemapResult(
                    entries = emptyList(),
                    success = false,
                    errorMessage = "HTTP ${response.code}: ${response.message}"
                )
            }

            val xmlContent = response.body?.string() ?: ""
            val entries = parseXmlContent(xmlContent)

            SitemapResult(
                entries = entries,
                success = true
            )

        } catch (e: Exception) {
            SitemapResult(
                entries = emptyList(),
                success = false,
                errorMessage = "请求sitemap失败: ${e.message}"
            )
        }
    }

    private fun parseXmlContent(xmlContent: String): List<SitemapEntry> {
        val entries = mutableListOf<SitemapEntry>()

        try {
            val doc: Document = Jsoup.parse(xmlContent, "", org.jsoup.parser.Parser.xmlParser())

            // 处理标准sitemap格式
            val urlElements = doc.select("url")
            for (urlElement in urlElements) {
                val loc = urlElement.select("loc").text()
                if (loc.isNotBlank()) {
                    val lastMod = urlElement.select("lastmod").text()
                    val changeFreq = urlElement.select("changefreq").text().takeIf { it.isNotBlank() }
                    val priority = urlElement.select("priority").text().toDoubleOrNull()

                    entries.add(
                        SitemapEntry(
                            url = loc,
                            lastModified = parseDateTime(lastMod),
                            changeFreq = changeFreq,
                            priority = priority
                        )
                    )
                }
            }

            // 处理sitemap index格式（包含其他sitemap的引用）
            if (entries.isEmpty()) {
                val sitemapElements = doc.select("sitemap")
                for (sitemapElement in sitemapElements) {
                    val loc = sitemapElement.select("loc").text()
                    if (loc.isNotBlank()) {
                        val lastMod = sitemapElement.select("lastmod").text()
                        entries.add(
                            SitemapEntry(
                                url = loc,
                                lastModified = parseDateTime(lastMod)
                            )
                        )
                    }
                }
            }

        } catch (e: Exception) {
            // XML解析失败时返回空列表
        }

        return entries
    }

    /**
     * 从网页URL提取详细内容
     */
    suspend fun extractPageContent(url: String): PageContent? {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (compatible; FeederBot/1.0)")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) return@withContext null

                val html = response.body?.string() ?: return@withContext null
                val doc = Jsoup.parse(html)

                PageContent(
                    title = extractTitle(doc, url),
                    description = extractDescription(doc),
                    publishDate = extractPublishDate(doc),
                    fullContent = extractFullContent(doc, url),
                    plainText = extractPlainTextContent(doc),
                    imageUrls = extractAllImages(doc, url),
                    featuredImage = extractFeaturedImage(doc, url)
                )

            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * 智能提取页面标题
     */
    private fun extractTitle(doc: Document, url: String): String {
        // 按优先级尝试不同的标题源
        val titleSources = listOf(
            { doc.select("meta[property=og:title]").attr("content") },
            { doc.select("meta[name=twitter:title]").attr("content") },
            { doc.select("h1").first()?.text() ?: "" },
            { doc.select("title").text() },
            { doc.select(".post-title, .entry-title, .article-title").text() }
        )

        for (source in titleSources) {
            val title = source().trim()
            if (title.isNotBlank() && title.length > 3) {
                return cleanTitle(title)
            }
        }

        // 最后降级到从URL提取
        return extractTitleFromUrl(url)
    }

    /**
     * 提取页面描述/摘要
     */
    private fun extractDescription(doc: Document): String {
        val descSources = listOf(
            { doc.select("meta[property=og:description]").attr("content") },
            { doc.select("meta[name=description]").attr("content") },
            { doc.select("meta[name=twitter:description]").attr("content") },
            { doc.select(".excerpt, .summary, .post-excerpt").text() },
            {
                // 提取第一段文字作为摘要
                val paragraphs = doc.select("p")
                paragraphs.firstOrNull { it.text().length > 50 }?.text() ?: ""
            }
        )

        for (source in descSources) {
            val desc = source().trim()
            if (desc.isNotBlank() && desc.length > 20) {
                return desc.take(300) // 限制长度
            }
        }

        return ""
    }

    /**
     * 智能提取发布时间
     */
    private fun extractPublishDate(doc: Document): LocalDateTime? {
        val dateSources = listOf(
            { doc.select("meta[property=article:published_time]").attr("content") },
            { doc.select("meta[name=publish_date]").attr("content") },
            { doc.select("meta[name=date]").attr("content") },
            { doc.select("time[datetime]").attr("datetime") },
            { doc.select("time[pubdate]").attr("pubdate") },
            { doc.select(".published, .date, .post-date, .entry-date").attr("datetime") },
            { doc.select(".published, .date, .post-date, .entry-date").text() }
        )

        for (source in dateSources) {
            val dateStr = source().trim()
            if (dateStr.isNotBlank()) {
                val parsed = parseDateTime(dateStr)
                if (parsed != null) return parsed
            }
        }

        // 尝试从URL中提取日期（如 /2025/06/27/article-title）
        return extractDateFromUrl(doc.location())
    }

    /**
     * 提取完整的文章内容（HTML格式，适合阅读）
     */
    private fun extractFullContent(doc: Document, baseUrl: String): String {
        // 首先尝试识别主要内容区域
        val contentElement = findMainContentElement(doc)

        if (contentElement != null) {
            // 清理内容并处理图片
            return cleanAndProcessContent(contentElement, baseUrl)
        }

        // 降级方案：提取所有段落和图片
        val fallbackContent = StringBuilder()
        doc.select("h1, h2, h3, h4, h5, h6, p, img, blockquote, ul, ol").forEach { element ->
            when (element.tagName()) {
                "img" -> {
                    val imgUrl = resolveImageUrl(element.attr("src"), baseUrl)
                    val alt = element.attr("alt")
                    fallbackContent.append("""<img src="$imgUrl" alt="$alt" style="max-width: 100%; height: auto;"><br>""")
                }
                else -> {
                    fallbackContent.append(element.outerHtml())
                }
            }
        }

        return fallbackContent.toString()
    }

    /**
     * 智能识别页面主要内容区域
     */
    private fun findMainContentElement(doc: Document): Element? {
        // 按优先级尝试常见的内容选择器
        val contentSelectors = listOf(
            "article",                          // HTML5语义化标签
            ".post-content, .entry-content",    // WordPress等CMS
            ".article-content, .article-body",  // 新闻网站
            ".content, .main-content",          // 通用内容区域
            "#content, #main",                  // ID选择器
            ".post, .entry",                    // 博客文章
            "[role=main]",                      // ARIA语义
            ".story-body, .article-text"        // 媒体网站
        )

        for (selector in contentSelectors) {
            val elements = doc.select(selector)
            if (elements.isNotEmpty()) {
                // 选择内容最丰富的元素
                val bestElement = elements.maxByOrNull { it.text().length }
                if (bestElement != null && bestElement.text().length > 200) {
                    return bestElement
                }
            }
        }

        // 最后尝试：寻找包含最多段落的div
        val divs = doc.select("div")
        return divs.maxByOrNull { div ->
            val paragraphs = div.select("p").size
            val textLength = div.ownText().length
            paragraphs * 100 + textLength // 加权评分
        }?.takeIf { it.select("p").size >= 3 }
    }

    /**
     * 清理和处理内容
     */
    private fun cleanAndProcessContent(element: Element, baseUrl: String): String {
        // 复制元素以避免修改原始DOM
        val cleanElement = element.clone()

        // 移除不需要的元素
        cleanElement.select("""
            script, style, noscript, iframe,
            .advertisement, .ads, .sidebar, .related, .comments,
            .social-share, .newsletter, .popup, .modal,
            .navigation, .nav, .menu, .header, .footer,
            [class*=ad], [id*=ad], [class*=popup], [class*=overlay]
        """.trimIndent()).remove()

        // 处理图片
        cleanElement.select("img").forEach { img ->
            val src = img.attr("src")
            if (src.isNotBlank()) {
                val fullUrl = resolveImageUrl(src, baseUrl)
                img.attr("src", fullUrl)
                // 添加响应式样式
                img.attr("style", "max-width: 100%; height: auto; margin: 10px 0;")

                // 如果没有alt属性，尝试从其他属性获取
                if (img.attr("alt").isBlank()) {
                    val alt = img.attr("title").ifBlank {
                        img.attr("data-alt").ifBlank { "图片" }
                    }
                    img.attr("alt", alt)
                }
            }
        }

        // 处理链接
        cleanElement.select("a").forEach { link ->
            val href = link.attr("href")
            if (href.isNotBlank() && !href.startsWith("http")) {
                link.attr("href", resolveImageUrl(href, baseUrl))
            }
        }

        // 清理空元素
        cleanElement.select("p:empty, div:empty, span:empty").remove()

        // 添加基础CSS样式使内容更易读
        val styledContent = """
            <div style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                        line-height: 1.6; color: #333; max-width: 800px; margin: 0 auto; padding: 20px;">
                ${cleanElement.html()}
            </div>
        """.trimIndent()

        return styledContent
    }

    /**
     * 提取纯文本内容（去除HTML标签）
     */
    private fun extractPlainTextContent(doc: Document): String {
        val contentElement = findMainContentElement(doc)

        return if (contentElement != null) {
            // 清理后提取文本
            val cleanElement = contentElement.clone()
            cleanElement.select("script, style, noscript").remove()

            // 在段落间添加换行
            cleanElement.select("p, br, div, h1, h2, h3, h4, h5, h6").forEach {
                it.append("\n")
            }

            cleanElement.text().replace(Regex("\\n+"), "\n\n").trim()
        } else {
            // 降级方案
            doc.select("p").text()
        }
    }

    /**
     * 提取页面中所有图片
     */
    private fun extractAllImages(doc: Document, baseUrl: String): List<String> {
        val contentElement = findMainContentElement(doc) ?: doc

        return contentElement.select("img")
            .mapNotNull { img ->
                val src = img.attr("src")
                if (src.isNotBlank()) resolveImageUrl(src, baseUrl) else null
            }
            .filter { url ->
                // 过滤掉明显的装饰性图片
                !url.contains(Regex("logo|icon|avatar|button|ad", RegexOption.IGNORE_CASE)) &&
                        !url.endsWith(".svg") // 通常是图标
            }
            .distinct()
    }

    /**
     * 提取特色图片
     */
    private fun extractFeaturedImage(doc: Document, baseUrl: String): String? {
        val imgSources = listOf(
            { doc.select("meta[property=og:image]").attr("content") },
            { doc.select("meta[name=twitter:image]").attr("content") },
            { doc.select(".featured-image img, .post-thumbnail img").attr("src") },
            { doc.select("article img").first()?.attr("src") ?: "" }
        )

        for (source in imgSources) {
            val imgUrl = source().trim()
            if (imgUrl.isNotBlank()) {
                return resolveImageUrl(imgUrl, baseUrl)
            }
        }

        return null
    }

    /**
     * 从URL路径中提取可能的日期
     */
    private fun extractDateFromUrl(url: String): LocalDateTime? {
        // 匹配 /YYYY/MM/DD/ 格式
        val datePattern = Regex("""/(\d{4})/(\d{1,2})/(\d{1,2})/""")
        val match = datePattern.find(url)

        return if (match != null) {
            try {
                val year = match.groupValues[1].toInt()
                val month = match.groupValues[2].toInt()
                val day = match.groupValues[3].toInt()
                LocalDateTime.of(year, month, day, 0, 0)
            } catch (e: Exception) {
                null
            }
        } else null
    }

    /**
     * 清理标题文本
     */
    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("""\s*\|\s*.*$"""), "") // 移除 "标题 | 网站名"
            .replace(Regex("""\s*-\s*.*$"""), "")  // 移除 "标题 - 网站名"
            .trim()
            .take(100) // 限制长度
    }

    /**
     * 解析相对图片URL为绝对URL
     */
    private fun resolveImageUrl(imgUrl: String, baseUrl: String): String {
        return when {
            imgUrl.startsWith("http") -> imgUrl
            imgUrl.startsWith("//") -> "https:$imgUrl"
            imgUrl.startsWith("/") -> {
                val base = baseUrl.split("/").take(3).joinToString("/")
                "$base$imgUrl"
            }
            else -> {
                val base = baseUrl.removeSuffix("/")
                "$base/$imgUrl"
            }
        }
    }

    private fun parseDateTime(dateStr: String): LocalDateTime? {
        if (dateStr.isBlank()) return null

        for (formatter in dateFormatters) {
            try {
                return LocalDateTime.parse(dateStr, formatter)
            } catch (e: DateTimeParseException) {
                // 尝试下一个格式
            }
        }

        return null
    }

    private fun normalizeBaseUrl(url: String): String {
        var normalized = url.trim()

        // 添加协议
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "https://$normalized"
        }

        // 移除尾部斜杠
        if (normalized.endsWith("/")) {
            normalized = normalized.dropLast(1)
        }

        return normalized
    }

    private fun extractTitleFromUrl(url: String): String {
        // 从URL中提取可能的标题
        val path = url.substringAfterLast("/")
        return path
            .replace("-", " ")
            .replace("_", " ")
            .replaceFirstChar { it.uppercaseChar() }
            .take(100) // 限制长度
    }

    /**
     * 过滤和排序sitemap条目
     */
    fun filterAndSort(
        entries: List<SitemapEntry>,
        maxEntries: Int = 50,
        contentKeywords: List<String> = listOf("exhibition", "event", "show", "news", "article"),
        excludePatterns: List<String> = listOf("/tag/", "/category/", "/page/", "/wp-admin/", "/wp-content/")
    ): List<SitemapEntry> {

        return entries
            // 过滤掉不相关的URL
            .filter { entry ->
                val url = entry.url.lowercase()

                // 排除明显的系统页面
                val isExcluded = excludePatterns.any { pattern -> url.contains(pattern) }
                if (isExcluded) return@filter false

                // 如果提供了关键词，优先包含相关内容
                if (contentKeywords.isNotEmpty()) {
                    contentKeywords.any { keyword -> url.contains(keyword) }
                } else {
                    true
                }
            }
            // 按最后修改时间降序排序，没有时间的放在最后
            .sortedWith(compareByDescending<SitemapEntry> { it.lastModified != null }
                .thenByDescending { it.lastModified })
            // 限制数量
            .take(maxEntries)
    }
}

/**
 * 使用示例和扩展函数
 */
object SitemapParserExtensions {

    /**
     * 将sitemap条目转换为类似RSS item的格式
     */
    data class PseudoRssItem(
        val title: String,
        val link: String,
        val pubDate: LocalDateTime?,
        val description: String = "",
        val fullContent: String = "",           // 完整HTML内容
        val plainText: String = "",             // 纯文本内容
        val imageUrls: List<String> = emptyList(), // 所有图片
        val featuredImage: String? = null
    )

    /**
     * 简化的解析接口，直接返回类RSS格式（包含完整内容提取）
     */
    suspend fun SitemapParser.parseToRssItems(
        baseUrl: String,
        maxItems: Int = 20,
        extractContent: Boolean = true
    ): List<PseudoRssItem> {
        val result = parseSitemap(baseUrl)
        if (!result.success) return emptyList()

        val filteredEntries = filterAndSort(result.entries, maxItems)

        return if (extractContent) {
            // 提取完整内容（较慢但内容丰富）
            filteredEntries.mapNotNull { entry ->
                val content = extractPageContent(entry.url)
                if (content != null) {
                    PseudoRssItem(
                        title = content.title,
                        link = entry.url,
                        pubDate = content.publishDate ?: entry.lastModified,
                        description = content.description,
                        fullContent = content.fullContent,
                        plainText = content.plainText,
                        imageUrls = content.imageUrls,
                        featuredImage = content.featuredImage
                    )
                } else {
                    // 降级到基础信息
                    PseudoRssItem(
                        title = extractTitleFromUrl(entry.url),
                        link = entry.url,
                        pubDate = entry.lastModified
                    )
                }
            }
        } else {
            // 仅使用sitemap信息（快速但内容简单）
            filteredEntries.map { entry ->
                PseudoRssItem(
                    title = extractTitleFromUrl(entry.url),
                    link = entry.url,
                    pubDate = entry.lastModified
                )
            }
        }
    }

    private fun extractTitleFromUrl(url: String): String {
        // 从URL中提取可能的标题
        val path = url.substringAfterLast("/")
        return path
            .replace("-", " ")
            .replace("_", " ")
            .replaceFirstChar { it.uppercaseChar() }
            .take(100) // 限制长度
    }
}
