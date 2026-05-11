package com.casttv.dlna.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.casttv.dlna.CastApplication
import com.casttv.dlna.R
import java.util.regex.Pattern

/**
 * 红果短剧无障碍服务
 * 
 * 核心功能：
 * 1. 监控红果短剧App的播放界面状态变化
 * 2. 从UI层级中提取视频URL信息
 * 3. 通过LocalBroadcast通知MainActivity更新界面
 */
class HongguoAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "HGAccessibility"
        
        // 广播Action：视频URL检测到
        const val ACTION_VIDEO_URL_DETECTED = "com.casttv.dlna.VIDEO_URL_DETECTED"
        const val EXTRA_VIDEO_URL = "video_url"
        const val EXTRA_APP_NAME = "app_name"
        
        // 红果短剧相关包名
        private val TARGET_PACKAGES = arrayOf(
            "com.kwai.video",           // 快手/红果短剧（可能）
            "com.smile.gifmaker",       // 红果短剧（主包名）
            "com.tencent.mm",           // 微信（备用）
            "cn.com.hongguo",          // 红果短剧（备选）
            "com.redfruit.video"       // 可能的包名
        )
        
        // 视频URL正则模式（m3u8/mp4）
        private val VIDEO_URL_PATTERNS = arrayOf(
            Pattern.compile("(https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(https?://[^\\s\"'<>]*\\.mp4[^\\s\"'<>]*)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(https?://[^\\s\"'<>]*(?:flv|mp3|aac)[^\\s\"'<>]*)", Pattern.CASE_INSENSITIVE)
        )
        
        // 播放界面关键词（用于判断是否在播放页面）
        private val PLAYING_PAGE_KEYWORDS = arrayOf(
            "播放", "play", "video", "视频", "剧集",
            "下一集", "上一集", "全屏", "清晰度", "倍速",
            "点赞", "收藏", "分享", "评论"
        )
        
        @Volatile
        var instance: HongguoAccessibilityService? = null
            private set
    
        fun isRunning(): Boolean = instance != null
    }

    private var isVideoPlaying = false
    private var lastDetectedUrl: String? = null
    private var currentPackageName: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        
        Log.i(TAG, "红果短剧无障碍服务已连接")
        
        // 配置无障碍服务
        val config = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED or 
                         AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) 
                        AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY else 0)
            packageNames = TARGET_PACKAGES
            notificationTimeout = 500
        }
        serviceInfo = config
        
        showNotification("服务已启动，请打开红果短剧播放视频")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    handleWindowStateChanged(event)
                }
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    handleViewClicked(event)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理事件异常: ${e.message}")
        }
    }

    /**
     * 处理窗口状态变化事件
     * 主要用于检测是否进入/退出视频播放界面
     */
    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""
        
        currentPackageName = packageName
        
        Log.d(TAG, "窗口切换: $packageName / $className")
        
        // 检查是否是目标App的Activity
        if (!TARGET_PACKAGES.contains(packageName)) return
        
        // 判断是否是视频播放页面
        isVideoPlaying = isVideoPlayerActivity(className, event)
        
        if (isVideoPlaying) {
            Log.i(TAG, "检测到视频播放页面")
            
            // 尝试从当前窗口内容提取视频URL
            extractVideoUrlFromWindow()
        }
    }

    /**
     * 处理视图点击事件
     * 用于捕获用户交互并提取可能的视频信息
     */
    private fun handleViewClicked(event: AccessibilityEvent) {
        if (!isVideoPlaying) return
        
        val source = event.source ?: return
        val contentDescription = source.contentDescription?.toString() ?: ""
        val text = source.text?.toString() ?: ""
        
        Log.d(TAG, "点击事件: text=$text, desc=$contentDescription")
        
        // 如果点击的是与视频相关的控件，重新尝试提取URL
        if (isVideoRelatedControl(contentDescription, text)) {
            extractVideoUrlFromWindow()
        }
    }

    /**
     * 判断是否是视频播放Activity
     */
    private fun isVideoPlayerActivity(className: String, event: AccessibilityEvent): Boolean {
        // 常见的视频播放器Activity命名模式
        val playerPatterns = arrayOf(
            ".*[Pp]layer.*", ".*[Vv]ideo.*", ".*[Mm]ovie.*",
            ".*PlayActivity.*", ".*VideoActivity.*", ".*PlayerActivity.*",
            ".*FullScreen.*", ".*Detail.*", ".*Episode.*",
            ".*剧集.*", ".*播放.*"
        )
        
        return playerPatterns.any { Pattern.matches(it, className) } ||
               className.contains("Player", ignoreCase = true) ||
               className.contains("Video", ignoreCase = true)
    }

    /**
     * 判断点击的控件是否与视频播放相关
     */
    private fun isVideoRelatedControl(desc: String, text: String): Boolean {
        val combined = "$desc $text".lowercase()
        return PLAYING_PAGE_KEYWORDS.any { combined.contains(it.lowercase()) }
    }

    /**
     * 从当前窗口内容中提取视频URL
     * 这是核心方法：遍历UI树查找包含视频URL的节点
     */
    private fun extractVideoUrlFromWindow() {
        try {
            val rootNode = rootInActiveWindow ?: run {
                Log.w(TAG, "无法获取根节点")
                return
            }
            
            val url = traverseNodeTree(rootNode)
            
            if (url != null && url != lastDetectedUrl && isValidVideoUrl(url)) {
                lastDetectedUrl = url
                
                Log.i(TAG, "✅ 检测到视频URL: ${url.take(80)}...")
                
                // 更新全局状态
                CastApplication.getInstance().updateVideoUrl(url)
                
                // 发送广播通知MainActivity
                sendVideoUrlBroadcast(url)
                
                // 显示提示
                showToast("检测到视频链接")
            }
        } catch (e: Exception) {
            Log.e(TAG, "提取视频URL失败: ${e.message}")
        }
    }

    /**
     * 递归遍历UI节点树，查找视频URL
     */
    private fun traverseNodeTree(node: AccessibilityNodeInfo): String? {
        var url: String? = null
        
        // 检查当前节点的文本内容
        node.text?.toString()?.let { text ->
            url = findVideoUrlInText(text)
            if (url != null) return@let
        }
        
        // 检查content description
        node.contentDescription?.toString()?.let { desc ->
            if (url == null) {
                url = findVideoUrlInText(desc)
            }
        }
        
        // 检查viewIdResourceName中的hint
        node.viewIdResourceName?.let { id ->
            if (url == null) {
                // 有些App会在WebView中加载视频，检查WebView URL
                if (id.contains("webview", ignoreCase = true) || 
                    id.contains("player", ignoreCase = true)) {
                    Log.d(TAG, "发现WebView/Player节点: $id")
                }
            }
        }
        
        // 递归遍历子节点
        if (url == null) {
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    url = traverseNodeTree(child)
                    if (url != null) {
                        child.recycle()
                        return url
                    }
                    child.recycle()
                }
            }
        }
        
        return url
    }

    /**
     * 在文本中查找视频URL
     */
    private fun findVideoUrlInText(text: String): String? {
        for (pattern in VIDEO_URL_PATTERNS) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                val foundUrl = matcher.group(1)
                if (isValidVideoUrl(foundUrl)) {
                    return foundUrl
                }
            }
        }
        return null
    }

    /**
     * 验证是否是有效的视频URL
     */
    private fun isValidVideoUrl(url: String): Boolean {
        return (url.endsWith(".m3u8") || url.contains(".m3u8") ||
                url.endsWith(".mp4") || url.contains(".mp4")) &&
               url.startsWith("http")
    }

    /**
     * 发送视频URL广播给MainActivity
     */
    private fun sendVideoUrlBroadcast(url: String) {
        val intent = Intent(ACTION_VIDEO_URL_DETECTED).apply {
            putExtra(EXTRA_VIDEO_URL, url)
            putExtra(EXTRA_APP_NAME, currentPackageName)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    /**
     * 显示Toast通知
     */
    private fun showToast(message: String) {
        android.widget.Toast.makeText(applicationContext, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * 显示前台服务通知
     */
    private fun showNotification(message: String) {
        // TODO: 实现前台服务通知
    }

    override fun onInterrupt() {
        Log.i(TAG, "无障碍服务被中断")
        instance = null
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "红果短剧无障碍服务已销毁")
    }
}
