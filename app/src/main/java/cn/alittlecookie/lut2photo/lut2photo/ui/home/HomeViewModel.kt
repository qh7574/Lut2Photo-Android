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
import cn.alittlecookie.lut2photo.lut2photo.model.LutItem
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
                val fileName = intent.getStringExtra("file_name") ?: ""
                
                _processedCount.value = processedCount
                if (_isMonitoring.value == true) {
                    _statusText.value = "监控中... (已处理: $processedCount 张)"
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
        } catch (e: Exception) {
            // 忽略取消注册时的异常
        }
    }
    
    fun setMonitoring(monitoring: Boolean) {
        _isMonitoring.value = monitoring
        if (monitoring) {
            // 开始监控时重置计数
            _processedCount.value = 0
            _statusText.value = "监控中... (已处理: 0 张)"
        } else {
            _statusText.value = "已停止监控"
            // 停止监控时保持当前计数，不重置
            val currentCount = _processedCount.value ?: 0
            _statusText.value = "已停止监控 (共处理: $currentCount 张)"
        }
        
        // 保存监控状态到SharedPreferences
        preferencesManager.isMonitoring = monitoring
    }
    
    fun updateStatus(status: String) {
        _statusText.value = status
    }
    
    fun incrementProcessedCount() {
        val current = _processedCount.value ?: 0
        _processedCount.value = current + 1
        if (_isMonitoring.value == true) {
            _statusText.value = "监控中... (已处理: ${current + 1} 张)"
        }
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
        } catch (e: Exception) {
            // 如果检查失败，重置状态
            _isMonitoring.value = false
            _statusText.value = "准备就绪"
            preferencesManager.isMonitoring = false
        }
    }
    
    // 新增：开始监控
    fun startMonitoring(
        lutItem: LutItem,
        inputFolder: String,
        outputFolder: String,
        strength: Float,
        quality: Int,
        ditherType: String
    ) {
        setMonitoring(true)
        // TODO: 实现实际的监控逻辑
        // 这里应该启动FolderMonitorService
    }
    
    // 新增：停止监控
    fun stopMonitoring() {
        setMonitoring(false)
        // TODO: 停止FolderMonitorService
    }
    
    // 新增：清空历史记录
    fun clearHistory() {
        _processingHistory.value = emptyList()
        // 同时清空SharedPreferences中的记录
        val prefs = getApplication<Application>().getSharedPreferences("processing_history", Context.MODE_PRIVATE)
        prefs.edit().remove("records").apply()
    }
    
    // 新增：加载处理历史
    fun loadProcessingHistory() {
        val prefs = getApplication<Application>().getSharedPreferences("processing_history", Context.MODE_PRIVATE)
        val records = prefs.getStringSet("records", emptySet()) ?: emptySet()
        
        val processingRecords = records.mapNotNull { recordString ->
            try {
                val parts = recordString.split("|")
                when {
                    parts.size >= 9 -> {
                        // 最新格式：timestamp|fileName|inputPath|outputPath|status|lutFileName|strength|quality|ditherType
                        cn.alittlecookie.lut2photo.lut2photo.model.ProcessingRecord(
                            timestamp = parts[0].toLong(),
                            fileName = parts[1],
                            inputPath = parts[2],
                            outputPath = parts[3],
                            status = parts[4],
                            lutFileName = parts[5],
                            strength = parts[6].toFloatOrNull() ?: 0f,
                            quality = parts[7].toIntOrNull() ?: 0,
                            ditherType = parts[8]
                        )
                    }
                    parts.size >= 6 -> {
                        // 旧格式：timestamp|fileName|inputPath|outputPath|status|lutFileName
                        cn.alittlecookie.lut2photo.lut2photo.model.ProcessingRecord(
                            timestamp = parts[0].toLong(),
                            fileName = parts[1],
                            inputPath = parts[2],
                            outputPath = parts[3],
                            status = parts[4],
                            lutFileName = parts[5]
                        )
                    }
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.timestamp }
        
        _processingHistory.value = processingRecords
    }
}
