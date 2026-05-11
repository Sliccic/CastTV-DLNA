package com.casttv.dlna.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.casttv.dlna.R
import com.casttv.dlna.dlna.DlnaCastService
import com.casttv.dlna.service.HongguoAccessibilityService

/**
 * 红果投屏助手 - 主界面Activity
 * 
 * 功能：
 * 1. 启动/停止无障碍服务监听红果短剧
 * 2. 显示检测到的视频URL
 * 3. 扫描并显示DLNA设备列表
 * 4. 控制DLNA投屏开始/停止
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvServiceStatus: TextView
    private lateinit var tvVideoUrl: TextView
    private lateinit var llDeviceList: LinearLayout
    private lateinit var btnToggleService: Button
    private lateinit var btnScanDevices: Button
    private lateinit var btnCastControl: Button
    private lateinit var btnStopCast: Button
    
    // DLNA服务绑定
    private var dlnaService: DlnaCastService? = null
    private val dlnaConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: IBinder?) {
            dlnaService = (service as DlnaCastService.DlnaBinder).getService()
            Log.i("MainActivity", "DLNA服务已绑定")
        }
        
        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            dlnaService = null
        }
    }

    // 选中的设备UDN
    private var selectedDeviceUdn: String? = null
    private var selectedDeviceName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        initViews()
        setupListeners()
        registerBroadcasts()
        handleIntentData()
        
        // 自动绑定DLNA服务
        bindDlnaService()
        
        updateServiceStatusUI()
    }

    private fun initViews() {
        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        tvVideoUrl = findViewById(R.id.tvVideoUrl)
        llDeviceList = findViewById(R.id.llDeviceList)
        btnToggleService = findViewById(R.id.btnToggleService)
        btnScanDevices = findViewById(R.id.btnScanDevices)
        btnCastControl = findViewById(R.id.btnCastControl)
        btnStopCast = findViewById(R.id.btnStopCast)
        
        tvVideoUrl.movementMethod = ScrollingMovementMethod.getInstance()
    }

    private fun setupListeners() {
        // 启动/停止无障碍服务按钮
        btnToggleService.setOnClickListener { toggleAccessibilityService() }
        
        // 扫描DLNA设备
        btnScanDevices.setOnClickListener { scanDlnaDevices() }
        
        // 开始/停止投屏
        btnCastControl.setOnClickListener { toggleCast() }
        btnStopCast.setOnClickListener { stopCast() }
    }

    /**
     * 处理从其他App分享过来的链接
     */
    private fun handleIntentData() {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (sharedText != null) {
                    processSharedText(sharedText)
                }
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.data
                if (uri != null) {
                    processSharedText(uri.toString())
                }
            }
        }
    }

    /**
     * 处理分享的文本（可能是视频URL）
     */
    private fun processSharedText(text: String) {
        Log.d("MainActivity", "收到分享文本: $text")
        
        // 尝试提取视频URL
        val url = extractVideoUrl(text)
        if (url != null) {
            com.casttv.dlna.CastApplication.getInstance().updateVideoUrl(url)
            tvVideoUrl.text = url
            Toast.makeText(this, "已提取视频链接", Toast.LENGTH_SHORT).show()
            Log.i("MainActivity", "提取到视频URL: ${url.take(60)}...")
        } else {
            tvVideoUrl.text = text
            Toast.makeText(this, "未识别到视频链接，请手动选择设备", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 从文本中提取视频URL
     */
    private fun extractVideoUrl(text: String): String? {
        val patterns = arrayOf(
            Regex("(https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*)", RegexOption.IGNORE_CASE),
            Regex("(https?://[^\\s\"'<>]+\\.mp4[^\\s\"'<>]*)", RegexOption.IGNORE_CASE),
            Regex("(https?://[^\\s\"'<>]*(?:flv|mp3)[^\\s\"'<>]*)", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                return match.value
            }
        }
        return null
    }

    /**
     * 切换无障碍服务状态
     */
    private fun toggleAccessibilityService() {
        if (HongguoAccessibilityService.isRunning()) {
            // 服务已在运行，提示用户
            Toast.makeText(this, "无障碍服务正在运行", Toast.LENGTH_SHORT).show()
        } else {
            // 引导用户去设置开启无障碍服务
            openAccessibilitySettings()
        }
        updateServiceStatusUI()
    }

    /**
     * 打开系统无障碍设置页面
     */
    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "请在设置中开启\"红果短剧DLNA投屏助手\"", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "无法打开无障碍设置", e)
            Toast.makeText(this, "请手动前往 设置 > 无障碍 开启本应用", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 扫描DLNA设备
     */
    private fun scanDlnaDevices() {
        btnScanDevices.isEnabled = false
        btnScanDevices.text = "🔍 扫描中..."
        llDeviceList.removeAllViews()
        
        // 添加扫描中提示
        val scanningView = layoutInflater.inflate(android.R.layout.simple_list_item_1, llDeviceList, false) as TextView
        scanningView.text = "正在搜索局域网内的DLNA设备..."
        llDeviceList.addView(scanningView)
        
        dlnaService?.searchDevices()
        
        // 延迟恢复按钮状态
        btnScanDevices.postDelayed({
            btnScanDevices.isEnabled = true
            btnScanDevices.text = getString(R.string.btn_scan_devices)
        }, 5000)
    }

    /**
     * 切换投屏状态
     */
    private fun toggleCast() {
        val videoUrl = com.casttv.dlna.CastApplication.currentVideoUrl
        
        if (videoUrl == null || videoUrl.isBlank()) {
            Toast.makeText(this, R.string.toast_no_video_url, Toast.LENGTH_SHORT).show()
            return
        }
        
        if (selectedDeviceUdn == null) {
            Toast.makeText(this, "请先选择一个DLNA设备", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (dlnaService?.isCasting() == true) {
            stopCast()
        } else {
            startCasting(videoUrl, selectedDeviceUdn!!)
        }
    }

    private fun startCasting(videoUrl: String, deviceUdn: String) {
        val success = dlnaService?.startCast(videoUrl, deviceUdn) ?: false
        
        if (success) {
            Toast.makeText(
                this,
                getString(R.string.toast_casting_started, selectedDeviceName),
                Toast.LENGTH_SHORT
            ).show()
            
            updateCastUI(casting = true)
        } else {
            Toast.makeText(this, "投屏失败，请检查设备和网络", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopCast() {
        dlnaService?.stopCasting()
        updateCastUI(casting = false)
        Toast.makeText(this, R.string.toast_casting_stopped, Toast.LENGTH_SHORT).show()
    }

    // ==================== UI更新方法 ====================

    private fun updateServiceStatusUI() {
        val isRunning = HongguoAccessibilityService.isRunning()
        
        tvServiceStatus.text = if (isRunning) 
            getString(R.string.status_service_running) else 
            getString(R.string.status_service_stopped)
        tvServiceStatus.setTextColor(resources.getColor(
            if (isRunning) R.color.status_running else R.color.status_stopped,
            theme
        ))
        
        btnToggleService.text = if (isRunning) 
            getString(R.string.btn_stop_service) else 
            getString(R.string.btn_start_service)
    }

    private fun updateCastUI(casting: Boolean) {
        btnCastControl.text = if (casting) "⏸ 暂停投屏" else "▶ 开始投屏"
        btnCastControl.backgroundTintList = resources.getColorStateList(
            if (casting) android.R.color.holo_orange_light else R.color.success_green,
            theme
        )
        btnStopCast.visibility = if (casting) View.VISIBLE else View.GONE
    }

    private fun addDeviceToList(device: DlnaCastService.DlnaDevice) {
        val view = Button(this).apply {
            text = "${device.name} (${device.type})"
            setOnClickListener { selectDevice(device.udn, device.name) }
            textSize = 14f
            isAllCaps = false
            setBackgroundResource(android.R.drawable.btn_default)
        }
        llDeviceList.addView(view)
    }

    private fun clearDeviceList() {
        llDeviceList.removeAllViews()
    }

    private fun selectDevice(udn: String, name: String) {
        selectedDeviceUdn = udn
        selectedDeviceName = name
        
        // 高亮选中的设备
        for (i in 0 until llDeviceList.childCount) {
            val child = llDeviceList.getChildAt(i)
            child.isSelected = (child as Button).text.contains(name)
        }
        
        btnCastControl.isEnabled = true
        Toast.makeText(this, "已选择: $name", Toast.LENGTH_SHORT).show()
        Log.i("MainActivity", "选中DLNA设备: $name ($udn)")
    }

    // ==================== 广播接收器 ====================

    private val videoUrlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val url = intent?.getStringExtra(HongguoAccessibilityService.EXTRA_VIDEO_URL)
            val appName = intent?.getStringExtra(HongguoAccessibilityService.EXTRA_APP_NAME)
            
            if (url != null) {
                runOnUiThread {
                    tvVideoUrl.text = url
                    btnCastControl.isEnabled = (selectedDeviceUdn != null)
                    Toast.makeText(
                        this@MainActivity,
                        "📺 从$appName 检测到视频链接",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                Log.i("MainActivity", "收到视频URL广播: ${url.take(50)}...")
            }
        }
    }

    private val deviceFoundReceiver = object : BroadcastReceiver() {
        @Suppress("UNCHECKED_CAST")
        override fun onReceive(context: Context?, intent: Intent?) {
            val devices = intent?.getSerializableExtra(DlnaCastService.EXTRA_DEVICES) as? ArrayList<*>
            
            devices?.let { list ->
                runOnUiThread {
                    clearDeviceList()
                    
                    if (list.isEmpty()) {
                        val emptyView = TextView(context).apply {
                            text = getString(R.string.toast_no_device_found)
                            setPadding(16, 16, 16, 16)
                        }
                        llDeviceList.addView(emptyView)
                    } else {
                        for (item in list) {
                            if (item is DlnaCastService.DlnaDevice) {
                                addDeviceToList(item)
                            }
                        }
                        
                        Toast.makeText(
                            context,
                            "发现 ${list.size} 个DLNA设备",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    private val castStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val casting = intent?.getBooleanExtra(DlnaCastService.EXTRA_CASTING, false) ?: false
            val deviceName = intent?.getStringExtra(DlnaCastService.EXTRA_DEVICE_NAME)
            
            runOnUiThread {
                updateCastUI(casting)
                
                if (casting && deviceName != null) {
                    tvServiceStatus.text = getString(R.string.status_connected, deviceName)
                    tvServiceStatus.setTextColor(resources.getColor(R.color.status_running, theme))
                }
            }
        }
    }

    private fun registerBroadcasts() {
        LocalBroadcastManager.getInstance(this).apply {
            registerReceiver(videoUrlReceiver, IntentFilter(HongguoAccessibilityService.ACTION_VIDEO_URL_DETECTED))
            registerReceiver(deviceFoundReceiver, IntentFilter(DlnaCastService.ACTION_DEVICE_FOUND))
            registerReceiver(castStatusReceiver, IntentFilter(DlnaCastService.ACTION_CAST_STATUS))
        }
    }

    private fun unregisterBroadcasts() {
        try {
            LocalBroadcastManager.getInstance(this).apply {
                unregisterReceiver(videoUrlReceiver)
                unregisterReceiver(deviceFoundReceiver)
                unregisterReceiver(castStatusReceiver)
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "取消注册广播失败", e)
        }
    }

    // ==================== Service绑定管理 ====================

    private fun bindDlnaService() {
        val intent = Intent(this, DlnaCastService::class.java)
        bindService(intent, dlnaConnection, BIND_AUTO_CREATE)
        
        // Android 9+ 需要前台权限才能启动前台服务
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            !Settings.canDrawOverlays(this)) {
            // TODO: 提示用户授予悬浮窗权限
        }
    }

    private fun unbindDlnaService() {
        try {
            unbindService(dlnaConnection)
        } catch (e: Exception) {
            Log.w("MainActivity", "解绑DLNA服务失败", e)
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatusUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterBroadcasts()
        unbindDlnaService()
    }
}
