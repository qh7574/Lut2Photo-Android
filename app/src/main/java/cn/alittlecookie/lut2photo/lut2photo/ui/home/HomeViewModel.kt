package cn.alittlecookie.lut2photo.lut2photo.ui.home

import android.app.ActivityManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import cn.alittlecookie.lut2photo.lut2photo.model.ProcessingRecord
import cn.alittlecookie.lut2photo.lut2photo.service.FolderMonitorService
import cn.alittlecookie.lut2photo.lut2photo.utils.PreferencesManager

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val _isMonitoring = MutableLiveData<Boolean>().apply {
        value = false
    }
    val isMonitoring: LiveData<Boolean> = _isMonitoring
    
    private val _statusText = MutableLiveData<String>().apply {
        value = "准备就绪"
    }
    val statusText: LiveData<String> = _statusText
    
    private val _processedCount = MutableLiveData<Int>().apply {
        value = 0
    }
    val processedCount: LiveData<Int> = _processedCount
    
    private val _processingHistory = MutableLiveData<List<ProcessingRecord>>().apply {
        value = emptyList()
    }
    val processingHistory: LiveData<List<ProcessingRecord>> = _processingHistory
    
    private val preferencesManager = PreferencesManager(application)
    
    private val processingUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "cn.alittlecookie.lut2photo.PROCESSING_UPDATE") {
                val processedCount = intent.getIntExtra("processed_count", 0)

                _processedCount.value = processedCount
                if (_isMonitoring.value == true) {
                    _statusText.value = "监控中..."
                }
            }
        }
    }
    
    init {
        // 应用启动时检查服务状态
        checkServiceStatus()
        
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
            _statusText.value = "监控中..."
        } else {
            _statusText.value = "已停止监控"
        }
        
        // 保存监控状态到SharedPreferences
        preferencesManager.isMonitoring = monitoring
    }

    // 添加恢复监控状态的方法
    fun restoreMonitoringState() {
        val wasMonitoring = preferencesManager.isMonitoring
        if (wasMonitoring) {
            // 检查服务是否真的在运行
            checkServiceStatus()
        } else {
            // 确保状态一致
            _isMonitoring.value = false
            _statusText.value = "准备就绪"
        }
    }
    
    private fun checkServiceStatus() {
        try {
            val activityManager = getApplication<Application>().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val runningServices = activityManager.getRunningServices(Integer.MAX_VALUE)
            
            val isServiceRunning = runningServices.any { serviceInfo ->
                serviceInfo.service.className == FolderMonitorService::class.java.name
            }
            
            _isMonitoring.value = isServiceRunning
            _statusText.value = if (isServiceRunning) "监控中..." else "准备就绪"
            
            // 同步PreferencesManager中的状态
            preferencesManager.isMonitoring = isServiceRunning
        } catch (_: Exception) {
            // 如果检查失败，重置状态
            _isMonitoring.value = false
            _statusText.value = "准备就绪"
            preferencesManager.isMonitoring = false
        }
    }

    // 新增：停止监控
    fun stopMonitoring() {
        setMonitoring(false)
        // TODO: 停止FolderMonitorService
    }

}
