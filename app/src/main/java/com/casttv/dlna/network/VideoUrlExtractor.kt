package com.casttv.dlna.network

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * 视频URL提取器
 * 
 * 功能：
 * 1. 解析红果短剧的分享链接，获取真实播放地址
 * 2. 支持m3u8、mp4等格式
 * 3. 处理重定向链
 */
class VideoUrlExtractor private constructor() {
    
    companion object {
        private const val TAG = "VideoUrlExtract"
        private val INSTANCE = VideoUrlExtractor()
        
        fun getInstance(): VideoUrlExtractor = INSTANCE
        
        // 红果短剧相关域名模式
        private val HONGGUO_DOMAINS = arrayOf(
            "redfruit", "hongguo", "kuaishou", "kwai",
            "smile.gifmaker", "gifmaker"
        )
        
        // 视频URL模式
        private val VIDEO_URL_PATTERNS = arrayOf(
            Pattern.compile("(https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*)"),
            Pattern.compile("(https?://[^\\s\"'<>]+\\.mp4[^\\s\"'<>]*)"),
            Pattern.compile("\"(https?://[^\"]*\\.(?:m3u8|mp4|flv)[^\"]*)\"")
        )
        
        // 常见CDN域名（用于识别真实视频URL）
        private val CDN_DOMAINS = arrayOf(
            ".cdn", ".cos.", ".oss-", ".aliyuncs",
            ".qbox.me", ".ksyun", ".hwcdn",
            ".bcebos", ".volcengine"
        )
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * 从分享链接中提取视频真实URL
     * 
     * @param shareUrl 红果短剧的分享链接或网页URL
     * @return 视频的真实播放地址（m3u8/mp4），未找到返回null
     */
    fun extractFromShareUrl(shareUrl: String): String? {
        return try {
            Log.i(TAG, "开始提取视频URL: ${shareUrl.take(60)}...")
            
            // 方式1：直接检查是否已经是视频URL
            if (isVideoUrl(shareUrl)) {
                Log.d(TAG, "直接是视频URL: $shareUrl")
                return resolveRedirects(shareUrl)
            }
            
            // 方式2：访问页面解析HTML/JS中的视频地址
            val pageContent = fetchPageContent(shareUrl)
            if (pageContent != null) {
                // 从HTML内容中查找视频URL
                val url = findVideoUrlInContent(pageContent)
                if (url != null) {
                    Log.i(TAG, "从页面内容提取到视频URL: ${url.take(60)}...")
                    return url
                }
                
                // 尝试从JSON数据中提取（很多App用API返回）
                val apiUrl = findApiUrlInContent(pageContent, shareUrl)
                if (apiUrl != null) {
                    val videoUrl = extractFromApi(apiUrl)
                    if (videoUrl != null) return videoUrl
                }
            }
            
            // 方式3：尝试常见的红果短剧API格式
            val videoId = extractVideoId(shareUrl)
            if (videoId != null) {
                val apiResult = tryHongguoApis(videoId, shareUrl)
                if (apiResult != null) return apiResult
            }
            
            Log.w(TAG, "未能提取到视频URL")
            null
            
        } catch (e: Exception) {
            Log.e(TAG, "提取视频URL失败", e)
            null
        }
    }

    /**
     * 获取页面内容
     */
    private fun fetchPageContent(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", 
                    "Mozilla/5.0 (Linux; Android 12; Pixel 6) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()
            
            val response = httpClient.newCall(request).execute()
            
            if (response.isSuccessful && response.body != null) {
                response.body!!.string()
            } else {
                Log.w(TAG, "请求页面失败: ${response.code}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取页面内容异常", e)
            null
        }
    }

    /**
     * 从HTML/JS内容中查找视频URL
     */
    private fun findVideoUrlInContent(content: String): String? {
        for (pattern in VIDEO_URL_PATTERNS) {
            val matcher = pattern.matcher(content)
            while (matcher.find()) {
                val url = matcher.group(1) ?: continue
                
                // 过滤掉明显的广告或不相关链接
                if (isLikelyRealVideoUrl(url)) {
                    return url.trim()
                }
            }
        }
        return null
    }

    /**
     * 判断是否可能是真实的视频URL（基于CDN域名等特征）
     */
    private fun isLikelyRealVideoUrl(url: String): Boolean {
        // 必须以http开头且是视频格式
        if (!isVideoUrl(url)) return false
        
        // 检查是否包含CDN特征域名（更可能是真实视频）
        return CDN_DOMAINS.any { url.lowercase().contains(it) } ||
               url.contains("/video") || url.contains("/media") ||
               url.contains("play") || url.contains("stream") ||
               url.contains(".ts") || url.contains("segment")
    }

    /**
     * 在页面内容中寻找可能的API端点
     */
    private fun findApiUrlInContent(content: String, baseUrl: String): String? {
        // 寻找类似 /api/video/xxx 或 JSON数据块
        val patterns = arrayOf(
            Pattern.compile("\"(?:video_url|play_url|stream_url|src)\":\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\"url\":\\s*\"(https?://[^\"]+\\.(?:m3u8|mp4)[^\"]*)\"", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(https?://[^\\s\"']+api[^\\s\"']*(?:video|play|stream)[^\\s\"]*)", Pattern.CASE_INSENSITIVE)
        )
        
        for (pattern in patterns) {
            val matcher = pattern.matcher(content)
            if (matcher.find()) {
                val found = matcher.group(1)?.trim() ?: continue
                
                // 如果是完整URL，尝试请求
                if (found.startsWith("http")) {
                    return found
                }
                
                // 如果是相对路径，拼接base URL
                if (found.startsWith("/")) {
                    val domain = baseUrl.substringBefore("/", 7 + baseUrl.indexOf("://"))
                    return "$domain$found"
                }
            }
        }
        return null
    }

    /**
     * 从API响应中提取视频URL
     */
    private fun extractFromApi(apiUrl: String): String? {
        return try {
            val content = fetchPageContent(apiUrl) ?: return null
            
            // 尝试解析JSON
            try {
                val json = JSONObject(content)
                
                // 常见的视频URL字段名
                val keys = arrayOf(
                    "data", "result", "video_info", "play_info",
                    "video_url", "url", "playUrl", "play_url",
                    "videoPlayAddr", "main_url", "cdn_url"
                )
                
                for (key in keys) {
                    val value = json.optString(key, "")
                    if (value.isNotBlank() && isVideoUrl(value)) {
                        return value
                    }
                    
                    // 可能嵌套在子对象中
                    if (json.has(key) && !json.isNull(key)) {
                        val subObj = json.getJSONObject(key)
                        for (subKey in keys) {
                            val subValue = subObj.optString(subKey, "")
                            if (subValue.isNotBlank() && isVideoUrl(subValue)) {
                                return subValue
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // 不是标准JSON，尝试正则提取
            }
            
            // 最后尝试正则
            findVideoUrlInContent(content)
            
        } catch (e: Exception) {
            Log.e(TAG, "从API提取视频URL失败", e)
            null
        }
    }

    /**
     * 从URL中提取视频ID
     */
    private fun extractVideoId(url: String): String? {
        // 常见模式：.../video/123456 或 ...?vid=123456 或 .../v/abcde
        val patterns = arrayOf(
            Pattern.compile("/video/(\\w+)"),
            Pattern.compile("[?&]v(?:id)?[=](\\w+)"),
            Pattern.compile("/v/(\\w+)"),
            Pattern.compile("/episode/(\\w+)")
        )
        
        for (pattern in patterns) {
            val matcher = pattern.matcher(url)
            if (matcher.find()) return matcher.group(1)
        }
        return null
    }

    /**
     * 尝试红果短剧的常见API格式
     */
    private fun tryHongguoApis(videoId: String, originalUrl: String): String? {
        // 这里可以根据实际的红果短剧API进行调整
        // 目前只是预留接口，需要抓包分析真实的API后补充
        
        val apiPatterns = arrayOf(
            "https://api.hongguo.com/video/$videoId/play",
            "https://hongguo.kuaishou.com/rest/wd/photo/info?photoId=$videoId",
            "https://www.redfruit.com/api/v1/videos/$videoId/url"
        )
        
        for (apiUrl in apiPatterns) {
            try {
                val result = extractFromApi(apiUrl)
                if (result != null) return result
            } catch (e: Exception) {
                Log.d(TAG, "尝试API失败: $apiUrl (${e.message})")
            }
        }
        
        return null
    }

    /**
     * 跟踪重定向获取最终URL
     */
    private fun resolveRedirects(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .head() // 使用HEAD方法减少流量
                .followRedirects(false)
                .build()
            
            var currentUrl = url
            
            // 最多跟踪5次重定向
            repeat(5) {
                val response = httpClient.newCall(request).execute()
                
                when (response.code) {
                    301, 302, 303, 307, 308 -> {
                        response.header("Location")?.let { location ->
                            currentUrl = if (location.startsWith("http")) location else {
                                val base = currentUrl.substringBeforeLast("/")
                                "$base/$location"
                            }
                        }
                    }
                    else -> return@repeat
                }
            }
            
            currentUrl
        } catch (e: Exception) {
            Log.e(TAG, "跟踪重定向失败", e)
            url
        }
    }

    private fun isVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.endsWith(".m3u8") ||
               lower.endsWith(".mp4") ||
               lower.contains(".m3u8") ||
               lower.contains(".mp4") ||
               lower.contains(".flv")
    }
}
