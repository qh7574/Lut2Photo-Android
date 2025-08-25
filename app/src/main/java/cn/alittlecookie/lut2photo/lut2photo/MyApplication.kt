package cn.alittlecookie.lut2photo.lut2photo

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager

/**
 * 自定义Application类
 * 手动初始化WorkManager以避免自动初始化时的依赖问题
 */
class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 手动初始化 WorkManager
        try {
            val config = Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.INFO)
                .build()

            WorkManager.initialize(this, config)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}