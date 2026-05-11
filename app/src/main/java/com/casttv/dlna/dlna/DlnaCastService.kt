package com.casttv.dlna.dlna

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.casttv.dlna.CastApplication
import com.casttv.dlna.R
import com.casttv.dlna.ui.MainActivity
import org.fourthline.cling.UpnpServiceConfiguration
import org.fourthline.cling.UpnpServiceImpl
import org.fourthline.cling.binding.annotations.AnnotationLocalServiceBinder
import org.fourthline.cling.binding.xml.UDA10DeviceDescriptorBinderImpl
import org.fourthline.cling.binding.xml.UDA10ServiceDescriptorBinderImpl
import org.fourthline.cling.model.DefaultUpnpServiceConfiguration
import org.fourthline.cling.model.meta.DeviceDetails
import org.fourthline.cling.model.meta.DeviceIdentity
import org.fourthline.cling.model.meta.LocalDevice
import org.fourthline.cling.model.meta.RemoteDevice
import org.fourthline.cling.model.meta.RemoteService
import org.fourthline.cling.model.types.UDADeviceType
import org.fourthline.cling.registry.DefaultRegistryListener
import org.fourthline.cling.support.avtransport.callback.Play
import org.fourthline.cling.support.avtransport.callback.Stop
import org.fourthline.cling.support.contentdirectory.callback.Browse
import org.fourthline.cling.support.model.DIDLObject
import org.fourthline.cling.support.model.Res
import org.fourthline.cling.support.model.container.Container
import org.fourthline.cling.support.model.item.VideoItem
import java.util.concurrent.CopyOnWriteArrayList

/**
 * DLNA投屏服务
 * 
 * 核心功能：
 * 1. 发现局域网内的DLNA设备（电视/盒子）
 * 2. 将视频URL推送到DLNA设备播放
 * 3. 管理投屏状态和控制
 */
class DlnaCastService : Service() {

    companion object {
        private const val TAG = "DlnaCast"
        const val NOTIFICATION_ID = 1001
        
        // 广播Action
        const val ACTION_DEVICE_FOUND = "com.casttv.dlna.DEVICE_FOUND"
        const val ACTION_CAST_STATUS = "com.casttv.dlna.CAST_STATUS"
        const val EXTRA_DEVICES = "devices"
        const val EXTRA_CASTING = "casting"
        const val EXTRA_DEVICE_NAME = "device_name"
        
        @Volatile
        var instance: DlnaCastService? = null
            private set
    }

    private var upnpService: UpnpServiceImpl? = null
    private val devices = CopyOnWriteArrayList<DlnaDevice>()
    private var currentDevice: DlnaDevice? = null
    
    // Binder供Activity绑定调用
    private val binder = DlnaBinder()

    inner class DlnaBinder : Binder() {
        fun getService(): DlnaCastService = this@DlnaCastService
    }

    // ==================== 设备发现监听器 ====================
    
    private val registryListener = object : DefaultRegistryListener() {

        override fun remoteDeviceAdded(registry: org.fourthline.cling.registry.Registry, device: RemoteDevice) {
            super.remoteDeviceAdded(registry, device)
            
            // 只关注MediaRenderer设备（DLNA渲染设备）
            if (isMediaRenderer(device)) {
                Log.i(TAG, "发现DLNA设备: ${getDeviceDisplayName(device)}")
                
                val dlnaDevice = DlnaDevice(
                    udn = device.identity.udn.toString(),
                    name = getDeviceDisplayName(device),
                    type = device.type.type,
                    location = device.details.baseURL.toString()
                )
                devices.add(dlnaDevice)
                sendDevicesBroadcast()
            }
        }

        override fun remoteDeviceRemoved(registry: org.fourthline.cling.registry.Registry, device: RemoteDevice) {
            super.remoteDeviceRemoved(registry, device)
            val removed = devices.removeIf { it.udn == device.identity.udn.toString() }
            if (removed) {
                Log.i(TAG, "DLNA设备离线: ${getDeviceDisplayName(device)}")
                sendDevicesBroadcast()
            }
        }

        private fun isMediaRenderer(device: RemoteDevice): Boolean {
            return device.type?.type == "MediaRenderer" ||
                   device.services.any { it.serviceType.type == "AVTransport" }
        }

        private fun getDeviceDisplayName(device: RemoteDevice): String {
            return device.details?.friendlyName 
                   ?: device.displayString 
                   ?: "Unknown Device"
        }
    }

    // ==================== Service生命周期 ====================

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        initUpnpService()
        Log.i(TAG, "DLNA投屏服务已启动")
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCasting()
        upnpService?.shutdown()
        upnpService = null
        instance = null
        Log.i(TAG, "DLNA投屏服务已停止")
    }

    // ==================== 初始化UPnP/DLNA ====================

    private fun initUpnpService() {
        try {
            val config: UpnpServiceConfiguration = DefaultUpnpServiceConfiguration(
                AnnotationLocalServiceBinder(),
                UDA10DeviceDescriptorBinderImpl(),
                UDA10ServiceDescriptorBinderImpl()
            )
            
            upnpService = UpnpServiceImpl(config)
            
            // 注册设备发现监听器
            upnpService?.registry?.addListener(registryListener)
            
            // 启动搜索DLNA设备
            searchDevices()
            
            Log.i(TAG, "UPnP服务初始化完成，开始搜索DLNA设备...")
        } catch (e: Exception) {
            Log.e(TAG, "初始化UPnP服务失败", e)
        }
    }

    /**
     * 搜索DLNA设备
     */
    fun searchDevices() {
        devices.clear()
        upnpService?.controlPoint?.search(UDADeviceType.MEDIA_RENDERER)
        Log.d(TAG, "正在搜索DLNA MediaRenderer设备...")
    }

    /**
     * 获取已发现的设备列表
     */
    fun getDevices(): List<DlnaDevice> = devices.toList()

    // ==================== 投屏控制 ====================

    /**
     * 开始投屏视频到指定设备
     */
    fun startCast(videoUrl: String, deviceUdn: String): Boolean {
        try {
            val targetDevice = devices.find { it.udn == deviceUdn } ?: run {
                Log.w(TAG, "未找到目标设备: $deviceUdn")
                return false
            }
            
            val remoteDevice = findRemoteDeviceByUdn(deviceUdn) ?: run {
                Log.w(TAG, "无法获取远程设备实例: $deviceUdn")
                return false
            }
            
            // 获取AVTransport服务
            val avTransportService = findAvTransportService(remoteDevice) ?: run {
                Log.w(TAG, "设备不支持AVTransport: ${targetDevice.name}")
                return false
            }
            
            Log.i(TAG, "开始投屏: ${targetDevice.name} -> $videoUrl")
            
            // 构建视频元数据并设置URI
            setVideoUri(avTransportService, videoUrl, targetDevice.name)
            
            // 发送Play命令
            executePlay(avTransportService)
            
            currentDevice = targetDevice
            CastApplication.getInstance().updateCastingStatus(true)
            
            // 更新通知
            updateNotification("正在投屏: ${targetDevice.name}")
            
            // 发送广播更新UI
            sendCastStatusBroadcast(true, targetDevice.name)
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "投屏失败", e)
            return false
        }
    }

    /**
     * 停止投屏
     */
    fun stopCasting() {
        try {
            currentDevice?.let { device ->
                val remoteDevice = findRemoteDeviceByUdn(device.udn)
                val avTransport = remoteDevice?.let { findAvTransportService(it) }
                
                avTransport?.let { executeStop(it) }
                
                Log.i(TAG, "已停止投屏: ${device.name}")
            }
            
            currentDevice = null
            CastApplication.getInstance().updateCastingStatus(false)
            updateNotification("DLNA投屏服务运行中")
            sendCastStatusBroadcast(false, null)
            
        } catch (e: Exception) {
            Log.e(TAG, "停止投屏失败", e)
        }
    }

    /**
     * 判断是否正在投屏
     */
    fun isCasting(): Boolean = currentDevice != null

    // ==================== 内部方法：DLNA控制 ====================

    private fun findRemoteDeviceByUdn(udn: String): RemoteDevice? {
        return upnpService?.registry?.getRemoteDevices()?.find { it.identity.udn.toString() == udn }
    }

    private fun findAvTransportService(device: RemoteDevice): RemoteService? {
        return device.findService(org.fourthline.cling.support.model.AVTransport.ID)
    }

    /**
     * 设置视频URI（DLNA SetAVTransportURI）
     */
    private fun setVideoUri(service: RemoteService, url: String, title: String) {
        // 创建视频项的DIDL元数据
        val videoId = "video-${System.currentTimeMillis()}"
        val res = Res(null, null, url.toURI()).apply {
            protocolInfo = "http-get:*:video/*:DLNA.ORG_OP=01;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        }
        
        val item = VideoItem(videoId, "parent-0", title, "", res).apply {
            addRes(res)
        }
        
        val didlXml = toDidlXml(item)
        
        val callback = object : org.fourthline.cling.support.avtransport.callback.SetAVTransportURI(service) {
            override fun success(actionCallback: org.fourthline.cling.controlpoint.ActionCallback?) {
                Log.d(TAG, "SetAVTransportURI成功")
            }
            
            override fun failure(invocation: org.fourthline.cling.controlpoint.ActionInvocation<*>?, 
                                  operation: org.fourthline.cling.model.message.UpnpResponse?, 
                                  defaultMsg: String?) {
                Log.e(TAG, "SetAVTransportURI失败: $defaultMsg")
            }
        }
        
        upnpService?.controlPoint?.execute(callback.apply {
            set("InstanceID", "0")
            set("CurrentURI", url)
            set("CurrentURIMetadata", didlXml)
        })
    }

    /**
     * 发送Play命令
     */
    private fun executePlay(service: RemoteService) {
        val callback = Play(service).apply {
            override fun success(actionCallback: org.fourthline.cling.controlpoint.ActionCallback?) {
                Log.i(TAG, "Play成功 - 视频开始播放")
            }
            
            override fun failure(invocation: org.fourthline.cling.controlpoint.ActionInvocation<*>?,
                                  operation: org.fourthline.cling.model.message.UpnpResponse?,
                                  defaultMsg: String?) {
                Log.e(TAG, "Play失败: $defaultMsg")
            }
        }.also { it.set("InstanceID", "0") }
        
        upnpService?.controlPoint?.execute(callback as org.fourthline.cling.controlpoint.ActionCallback)
    }

    /**
     * 发送Stop命令
     */
    private fun executeStop(service: RemoteService) {
        val callback = Stop(service).apply {
            override fun success(actionCallback: org.fourthline.cling.controlpoint.ActionCallback?) {
                Log.i(TAG, "Stop成功 - 视频已停止")
            }
            
            override fun failure(invocation: org.fourthline.cling.controlpoint.ActionInvocation<*>?,
                                  operation: org.fourthline.cling.model.message.UpnpResponse?,
                                  defaultMsg: String?) {
                Log.e(TAG, "Stop失败: $defaultMsg")
            }
        }.also { it.set("InstanceID", "0") }
        
        upnpService?.controlPoint?.execute(callback as org.fourthline.cling.controlpoint.ActionCallback)
    }

    /**
     * 生成DIDL XML元数据
     */
    private fun toDidlXml(item: VideoItem): String {
        val sb = StringBuilder()
        sb.append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
        sb.append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
        sb.append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" ")
        sb.append("xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\">")
        
        sb.append("<item id=\"${item.id}\" parentID=\"${item.parentID}\" restricted=\"1\">")
        sb.append("<dc:title>${escapeXml(item.title)}</dc:title>")
        sb.append("<upnp:class>object.item.videoItem</upnp:class>")
        
        for (res in item.resources) {
            sb.append("<res protocolInfo=\"${res.protocolInfo}\">${escapeXml(res.value)}</res>")
        }
        
        sb.append("</item>")
        sb.append("</DIDL-Lite>")
        
        return sb.toString()
    }

    private fun escapeXml(text: String): String {
        return text.replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
                  .replace("\"", "&quot;")
                  .replace("'", "&apos;")
    }

    // ==================== 广播通信 ====================

    private fun sendDevicesBroadcast() {
        val intent = Intent(ACTION_DEVICE_FOUND).apply {
            putExtra(EXTRA_DEVICES, ArrayList(devices))
        }
        LocalBroadcastManager.getInstance(this@DlnaCastService).sendBroadcast(intent)
    }

    private fun sendCastStatusBroadcast(casting: Boolean, deviceName: String?) {
        val intent = Intent(ACTION_CAST_STATUS).apply {
            putExtra(EXTRA_CASTING, casting)
            putExtra(EXTRA_DEVICE_NAME, deviceName)
        }
        LocalBroadcastManager.getInstance(this@DlnaCastService).sendBroadcast(intent)
    }

    // ==================== 通知管理 ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                getString(R.string.notification_channel_name),
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }
            
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String = getString(R.string.notification_content)): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, getString(R.string.notification_channel_name))
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(contentText))
    }

    // ==================== 数据类 ====================
    
    data class DlnaDevice(
        val udn: String,
        val name: String,
        val type: String,
        val location: String
    )
}
