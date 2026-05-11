package com.casttv.dlna

import android.app.Application
import android.util.Log

/**
 * CastTV-DLNA Application
 * 
 * 全局Application类，初始化DLNA和网络组件。
 */
class CastApplication : Application() {

    companion object {
        private const val TAG = "CastApp"
        
        @Volatile
        private var instance: CastApplication? = null
        
        fun getInstance(): CastApplication = instance!!
        
        // 全局状态：当前检测到的视频URL
        var currentVideoUrl: String? = null
            private set
            
        // 全局状态：是否正在投屏
        var isCasting: Boolean = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "CastTV-DLNA Application initialized")
    }
    
    /**
     * 更新当前视频URL（由无障碍服务调用）
     */
    fun updateVideoUrl(url: String?) {
        synchronized(this) {
            currentVideoUrl = url
            Log.d(TAG, "Video URL updated: ${url?.take(50)}...")
        }
    }
    
    /**
     * 更新投屏状态（由DLNA服务调用）
     */
    fun updateCastingStatus(casting: Boolean) {
        synchronized(this) {
            isCasting = casting
            Log.d(TAG, "Casting status updated: $casting")
        }
    }
}
