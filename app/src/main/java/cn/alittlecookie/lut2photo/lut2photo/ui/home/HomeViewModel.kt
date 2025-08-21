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
        // 修复：初始值应该从保存的状态恢复，而不是硬编码为false
        value = PreferencesManager(getApplication()).monitoringSwitchEnabled
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
                    } catch (e: Exception) {
                        0
                    }
                _processedCount.value = processedCount
                if (_isMonitoring.value == true) {
                    _statusText.value = "status_monitoring"
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
        
        // 保存监控状态到SharedPreferences
        preferencesManager.isMonitoring = monitoring
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

        // 修复：状态文本应该基于实际服务状态
        if (serviceRunning) {
            _statusText.value = getApplication<Application>().getString(R.string.status_monitoring)
            Log.d(TAG, "服务运行中，更新状态文本为监控中")
        } else {
            _statusText.value =
                getApplication<Application>().getString(R.string.status_monitoring_stopped)
            Log.d(TAG, "服务未运行，更新状态文本为已停止")
        }

        // 修复：只有在用户明确启动服务时才同步开关状态，避免意外的服务残留导致开关自动打开
        if (serviceRunning && !savedSwitchState) {
            // 如果服务在运行但UI显示关闭，可能是服务异常残留
            Log.w(TAG, "检测到服务异常残留，强制停止服务")
            // 修复：使用正确的action常量
            val stopIntent = Intent(getApplication(), FolderMonitorService::class.java).apply {
                action = FolderMonitorService.ACTION_STOP_MONITORING  // ✅ 使用正确的常量
            }
            getApplication<Application>().startService(stopIntent)

            // 确保状态文本显示为已停止
            _statusText.value =
                getApplication<Application>().getString(R.string.status_monitoring_stopped)
        } else if (!serviceRunning && savedSwitchState) {
            // 如果UI显示开启但服务未运行，同步UI状态为关闭
            Log.d(TAG, "服务未运行但UI显示开启，同步UI状态为关闭")
            preferencesManager.monitoringSwitchEnabled = false
            _isMonitoring.value = false
        }

        Log.d(
            TAG,
            "监控状态检查完成 - 服务运行: $serviceRunning, 保存状态: ${preferencesManager.monitoringSwitchEnabled}"
        )
    }

}
