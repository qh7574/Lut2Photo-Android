package cn.alittlecookie.lut2photo.lut2photo.ui.home

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import cn.alittlecookie.lut2photo.lut2photo.R
import cn.alittlecookie.lut2photo.lut2photo.service.FolderMonitorService
import cn.alittlecookie.lut2photo.lut2photo.utils.PreferencesManager

@SuppressLint("UnspecifiedRegisterReceiverFlag")
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "HomeViewModel"
    }

    private val _isMonitoring = MutableLiveData<Boolean>().apply {
        // 修复：初始值设为false，等待restoreMonitoringState()来设置正确的值
        // 不要在这里读取保存的状态，因为需要先检查服务是否真的在运行
        value = false
    }
    val isMonitoring: LiveData<Boolean> = _isMonitoring
    
    private val _statusText = MutableLiveData<String>().apply {
        value = getApplication<Application>().getString(R.string.status_ready)
    }
    val statusText: LiveData<String> = _statusText
    
    private val _processedCount = MutableLiveData<Int>().apply {
        value = 0
    }

    private val preferencesManager = PreferencesManager(application)
    
    private val processingUpdateReceiver = object : BroadcastReceiver() {
        private var lastUpdateTime = 0L

        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "cn.alittlecookie.lut2photo.PROCESSING_UPDATE") {
                val currentTime = System.currentTimeMillis()
                // 优化：限制UI更新频率，最多每3秒更新一次
                if (currentTime - lastUpdateTime > 3000) {
                    val processedCount = try {
                        intent.getIntExtra("processed_count", 0)
                    } catch (_: Exception) {
                        0
                    }
                _processedCount.value = processedCount
                if (_isMonitoring.value == true) {
                    _statusText.value =
                        getApplication<Application>().getString(R.string.status_monitoring)
                }
                    lastUpdateTime = currentTime
                }
            }
        }
    }
    
    init {
        // 移除自动检查服务状态，避免强制更新UI
        // 注册广播接收器
        val filter = IntentFilter("cn.alittlecookie.lut2photo.PROCESSING_UPDATE")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            getApplication<Application>().registerReceiver(
                processingUpdateReceiver, 
                filter, 
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            getApplication<Application>().registerReceiver(processingUpdateReceiver, filter)
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        try {
            getApplication<Application>().unregisterReceiver(processingUpdateReceiver)
        } catch (_: Exception) {
            // 忽略取消注册时的异常
        }
    }
    
    fun setMonitoring(monitoring: Boolean) {
        _isMonitoring.value = monitoring
        if (monitoring) {
            // 开始监控时重置计数
            _processedCount.value = 0
            _statusText.value = getApplication<Application>().getString(R.string.status_monitoring)
        } else {
            _statusText.value =
                getApplication<Application>().getString(R.string.status_monitoring_stopped)
        }
        
        // 修复：同时保存两个状态
        // monitoringSwitchEnabled: UI开关状态（用户意图）
        // isMonitoring: 服务运行状态（实际状态）
        preferencesManager.monitoringSwitchEnabled = monitoring
        preferencesManager.isMonitoring = monitoring
        
        Log.d(TAG, "设置监控状态: monitoring=$monitoring")
    }

    // 检查服务是否正在运行（不更新UI）
    private fun isServiceRunning(): Boolean {
        return try {
            val activityManager = getApplication<Application>().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)

            runningServices.any { serviceInfo ->
                serviceInfo.service.className == FolderMonitorService::class.java.name
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查服务状态失败", e)
            false
        }
    }

    // 恢复监控状态（只在必要时更新UI）
    fun restoreMonitoringState() {
        val serviceRunning = isServiceRunning()
        val savedSwitchState = preferencesManager.monitoringSwitchEnabled

        Log.d(TAG, "监控状态检查 - 服务运行: $serviceRunning, 保存的UI状态: $savedSwitchState")

        // 修复：根据实际情况同步状态
        if (serviceRunning) {
            // 服务正在运行，同步UI状态为开启
            _statusText.value = getApplication<Application>().getString(R.string.status_monitoring)
            _isMonitoring.value = true
            // 只有当保存的状态与实际不符时才更新
            if (!savedSwitchState) {
                preferencesManager.monitoringSwitchEnabled = true
                Log.d(TAG, "服务运行中但开关状态为关闭，同步为开启")
            } else {
                Log.d(TAG, "服务运行中，UI状态已是开启")
            }
        } else {
            // 服务未运行
            _statusText.value = getApplication<Application>().getString(R.string.status_monitoring_stopped)
            
            // 修复：先检查状态一致性，再设置UI
            if (savedSwitchState) {
                // 状态不一致：开关为开启但服务未运行
                Log.w(TAG, "检测到状态不一致：开关为开启但服务未运行，修正为关闭")
                _isMonitoring.value = false
                preferencesManager.monitoringSwitchEnabled = false
            } else {
                // 状态一致：开关为关闭，服务也未运行
                _isMonitoring.value = false
                Log.d(TAG, "服务未运行，开关状态为关闭，保持一致")
            }
        }

        Log.d(
            TAG,
            "监控状态检查完成 - 服务运行: $serviceRunning, UI状态: ${_isMonitoring.value}, 保存状态: ${preferencesManager.monitoringSwitchEnabled}"
        )
    }

}
