package cn.alittlecookie.lut2photo.lut2photo.service

// 添加水印处理相关导入
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import cn.alittlecookie.lut2photo.lut2photo.MainActivity
import cn.alittlecookie.lut2photo.lut2photo.R
import cn.alittlecookie.lut2photo.lut2photo.core.ILutProcessor
import cn.alittlecookie.lut2photo.lut2photo.core.ThreadManager
import cn.alittlecookie.lut2photo.lut2photo.core.WatermarkProcessor
import cn.alittlecookie.lut2photo.lut2photo.filetracker.FileRecord
import cn.alittlecookie.lut2photo.lut2photo.filetracker.FileTrackerConfig
import cn.alittlecookie.lut2photo.lut2photo.filetracker.FileTrackerManager
import cn.alittlecookie.lut2photo.lut2photo.utils.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class FolderMonitorService : Service() {

    companion object {
        private const val TAG = "FolderMonitorService"
        const val CHANNEL_ID = "folder_monitor_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START_MONITORING = "start_monitoring"
        const val ACTION_STOP_MONITORING = "stop_monitoring"
        const val ACTION_UPDATE_LUT_CONFIG = "update_lut_config"
        const val EXTRA_INPUT_FOLDER = "input_folder"
        const val EXTRA_OUTPUT_FOLDER = "output_folder"
        const val EXTRA_LUT_FILE_PATH = "lut_file_path"
        const val EXTRA_LUT2_FILE_PATH = "lut2_file_path"
        const val EXTRA_STRENGTH = "strength"
        const val EXTRA_LUT2_STRENGTH = "lut2_strength"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_DITHER = "dither"
        const val EXTRA_PROCESS_NEW_FILES_ONLY = "process_new_files_only"
        
        // 监控状态广播
        const val ACTION_MONITORING_STATUS_UPDATE = "cn.alittlecookie.lut2photo.MONITORING_STATUS_UPDATE"
        const val EXTRA_STATUS_MESSAGE = "status_message"
        const val EXTRA_IS_MONITORING = "is_monitoring"
        
        // 状态查询广播
        const val ACTION_QUERY_STATUS = "cn.alittlecookie.lut2photo.QUERY_MONITORING_STATUS"
    }


    private var isLutLoaded = false
    private var isMonitoring = false
    private var monitoringJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var processedCount = 0
    private var currentLutName = ""
    
    // 当前状态消息，用于状态查询时返回准确的实时状态
    private var currentStatusMessage: String = "监控未启动"

    // 添加图片完整性校验相关的变量
    private val incompleteFiles = mutableSetOf<String>()
    private val fileRetryCount = mutableMapOf<String, Int>()
    private val maxRetryCount = 10 // 最大重试次数

    // 监控参数
    private var inputFolderUri: String = ""
    private var outputFolderUri: String = ""
    private var lutFilePath: String = ""
    private var lut2FilePath: String = ""
    private var processingParams: ILutProcessor.ProcessingParams? = null
    private var currentLut2Name = ""
    private var processNewFilesOnly: Boolean = false

    // 统一集合声明
    // 添加处理中的文件跟踪
    private val processingFiles = mutableSetOf<String>()

    private val completedFiles = mutableSetOf<String>()
    
    // 优化：添加文件处理状态缓存，避免重复查询 SharedPreferences
    private val processedFilesCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private var wakeLock: PowerManager.WakeLock? = null
    private val restartHandler = Handler(Looper.getMainLooper())
    private val restartRunnable = Runnable {
        if (isMonitoring) {
            restartService()
        }
    }
    
    // FileTracker组件
    private var fileTrackerManager: FileTrackerManager? = null
    
    // 输出目录缓存
    private var outputDir: DocumentFile? = null
    
    private fun getOutputDir(): DocumentFile? {
        if (outputDir == null && outputFolderUri.isNotEmpty()) {
            outputDir = DocumentFile.fromTreeUri(this, outputFolderUri.toUri())
        }
        return outputDir
    }

    private lateinit var threadManager: ThreadManager

    // 添加水印处理器和偏好设置管理器
    private lateinit var watermarkProcessor: WatermarkProcessor
    private lateinit var preferencesManager: PreferencesManager

    // 处理器设置变化和LUT配置变化广播接收器
    private val configChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "cn.alittlecookie.lut2photo.PROCESSOR_SETTING_CHANGED" -> {
                    val processorType = intent.getStringExtra("processorType")
                    Log.d(TAG, "Received processor setting change: $processorType")
                    threadManager.updateProcessorFromSettings()
                    Log.d(TAG, "ThreadManager processor setting updated")
                }

                "cn.alittlecookie.lut2photo.LUT_CONFIG_CHANGED" -> {
                    Log.d(TAG, "========== 接收到LUT配置变化广播 ==========")
                    Log.d(TAG, "当前监控状态: isMonitoring=$isMonitoring")
                    Log.d(TAG, "当前配置: strength=${processingParams?.strength}, quality=${processingParams?.quality}")
                    serviceScope.launch {
                        Log.d(TAG, "开始执行配置重新加载协程")
                        reloadLutConfiguration()
                        Log.d(TAG, "配置重新加载协程执行完成")
                        Log.d(TAG, "新配置: strength=${processingParams?.strength}, quality=${processingParams?.quality}")
                    }
                }
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "FolderMonitorService onCreate")

        // 初始化ThreadManager
        threadManager = ThreadManager(this)

        // 初始化水印处理器和偏好设置管理器
        watermarkProcessor = WatermarkProcessor(this)
        preferencesManager = PreferencesManager(this)

        createNotificationChannel()
        // 注册广播接收器
        val intentFilter = IntentFilter().apply {
            addAction("cn.alittlecookie.lut2photo.PROCESSOR_SETTING_CHANGED")
            addAction("cn.alittlecookie.lut2photo.LUT_CONFIG_CHANGED")
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                configChangeReceiver,
                intentFilter,
                RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(
                configChangeReceiver,
                intentFilter
            )
        }

        // 添加：启动时同步处理器设置
        threadManager.updateProcessorFromSettings()

        // 获取WakeLock以防止系统休眠
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "FolderMonitorService::WakeLock"
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 修复：只有在实际开始监控时才显示启动通知
        when (intent?.action) {
            ACTION_START_MONITORING -> {
                // 显示初始通知
                val initialNotification = createNotification("文件夹监控服务", "等待状态更新...")
                startForeground(NOTIFICATION_ID, initialNotification)

                // 获取WakeLock
                wakeLock?.takeIf { !it.isHeld }?.acquire(10 * 60 * 1000L /*10 minutes*/)

                val inputFolder =
                    intent.getStringExtra(EXTRA_INPUT_FOLDER) ?: return START_REDELIVER_INTENT
                val outputFolder =
                    intent.getStringExtra(EXTRA_OUTPUT_FOLDER) ?: return START_REDELIVER_INTENT
                val lutFilePath =
                    intent.getStringExtra(EXTRA_LUT_FILE_PATH) ?: return START_REDELIVER_INTENT
                val lut2FilePath = intent.getStringExtra(EXTRA_LUT2_FILE_PATH) ?: ""
                val strength = intent.getIntExtra(EXTRA_STRENGTH, 100)
                val lut2Strength = intent.getIntExtra(EXTRA_LUT2_STRENGTH, 100)
                val quality = intent.getIntExtra(EXTRA_QUALITY, 90)
                val dither = intent.getStringExtra(EXTRA_DITHER) ?: "none"
                val processNewFilesOnly = intent.getBooleanExtra(EXTRA_PROCESS_NEW_FILES_ONLY, false)

                startMonitoring(
                    inputFolder,
                    outputFolder,
                    strength,
                    quality,
                    dither,
                    lutFilePath,
                    lut2FilePath,
                    lut2Strength,
                    processNewFilesOnly
                )
            }

            ACTION_UPDATE_LUT_CONFIG -> {
                // 更新LUT配置（在监控过程中动态更新）
                if (isMonitoring) {
                    serviceScope.launch {
                        reloadLutConfiguration()
                    }
                } else {
                    Log.w(TAG, "服务未在监控中，忽略LUT配置更新")
                }
            }
            ACTION_STOP_MONITORING -> {
                stopMonitoring()
                stopSelf()  // 停止服务
                return START_NOT_STICKY  // 不要重启服务
            }
            ACTION_QUERY_STATUS -> {
                // 响应状态查询请求，直接返回当前状态消息
                Log.d(TAG, "收到状态查询请求")
                Log.d(TAG, "  - 当前监控状态: isMonitoring=$isMonitoring")
                Log.d(TAG, "  - 当前状态消息: $currentStatusMessage")
                
                // 直接使用维护的状态消息，准确反映实时状态（扫描、标记、监控中等）
                broadcastMonitoringStatus(currentStatusMessage)
                // 不需要启动前台服务，只是响应查询
            }
            else -> {
                // 修复：如果没有明确的action，不显示启动通知
                Log.w(TAG, "服务启动但没有明确的action,已停止服务")
                stopMonitoring()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        return START_REDELIVER_INTENT
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW // 改为LOW以减少用户干扰
        ).apply {
            description = "文件夹监控服务通知"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(
        title: String,
        content: String = "",
        progress: Int = -1,
        maxProgress: Int = 100
    ): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        // 添加进度条支持
        if (progress >= 0) {
            builder.setProgress(maxProgress, progress, false)
        }

        return builder.build()
    }
    
    /**
     * 发送监控状态广播到UI（持久状态）
     * @param statusMessage 状态消息
     */
    private fun broadcastMonitoringStatus(statusMessage: String) {
        // 同步更新当前状态消息，用于状态查询
        currentStatusMessage = statusMessage
        
        val intent = Intent(ACTION_MONITORING_STATUS_UPDATE).apply {
            // 设置包名，使其成为显式广播，这样RECEIVER_NOT_EXPORTED的接收器才能收到
            setPackage(packageName)
            putExtra(EXTRA_STATUS_MESSAGE, statusMessage)
            putExtra(EXTRA_IS_MONITORING, isMonitoring)
        }
        
        Log.d(TAG, "========== 准备发送监控状态广播（持久） ==========")
        Log.d(TAG, "  - Action: $ACTION_MONITORING_STATUS_UPDATE")
        Log.d(TAG, "  - Package: $packageName")
        Log.d(TAG, "  - 状态消息: $statusMessage")
        Log.d(TAG, "  - 是否监控中: $isMonitoring")
        
        sendBroadcast(intent)
        
        Log.d(TAG, "  - 广播已发送，currentStatusMessage已更新")
    }
    
    /**
     * 发送临时状态广播到UI（不更新currentStatusMessage）
     * 用于配置更新等短暂提示，不会影响状态查询
     * @param statusMessage 临时状态消息
     * @param autoRestoreDelayMs 自动恢复延迟（毫秒），默认3秒后恢复到持久状态
     */
    private fun broadcastTemporaryStatus(statusMessage: String, autoRestoreDelayMs: Long = 3000) {
        // 不更新 currentStatusMessage，保持持久状态
        
        val intent = Intent(ACTION_MONITORING_STATUS_UPDATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS_MESSAGE, statusMessage)
            putExtra(EXTRA_IS_MONITORING, isMonitoring)
        }
        
        Log.d(TAG, "========== 准备发送临时状态广播 ==========")
        Log.d(TAG, "  - 临时状态消息: $statusMessage")
        Log.d(TAG, "  - 持久状态消息: $currentStatusMessage (不变)")
        Log.d(TAG, "  - 自动恢复延迟: ${autoRestoreDelayMs}ms")
        
        sendBroadcast(intent)
        
        Log.d(TAG, "  - 临时广播已发送")
        
        // 延迟后自动恢复到持久状态
        Handler(Looper.getMainLooper()).postDelayed({
            if (isMonitoring) {
                Log.d(TAG, "自动恢复到持久状态: $currentStatusMessage")
                broadcastMonitoringStatus(currentStatusMessage)
            }
        }, autoRestoreDelayMs)
    }

    private fun startMonitoring(
        inputFolder: String,
        outputFolder: String,
        strength: Int,
        quality: Int,
        dither: String,
        lutFilePath: String,
        lut2FilePath: String = "",
        lut2Strength: Int = 100,
        processNewFilesOnly: Boolean = false
    ) {
        if (isMonitoring) {
            Log.w(TAG, "监控已在运行中")
            return
        }

        // 添加：每次开始监控时都更新处理器设置
        Log.d(TAG, "开始监控前更新处理器设置")
        threadManager.updateProcessorFromSettings()

        // 保存参数
        this.inputFolderUri = inputFolder
        this.outputFolderUri = outputFolder
        this.lutFilePath = lutFilePath
        this.lut2FilePath = lut2FilePath
        this.processNewFilesOnly = processNewFilesOnly

        // 修复：只在开关打开时才标记现有文件
        if (processNewFilesOnly) {
            Log.d(TAG, "仅处理新增文件模式已启用，开始标记现有文件")
            markExistingFilesAsProcessed(inputFolder)
        } else {
            Log.d(TAG, "仅处理新增文件模式未启用，不标记现有文件")
        }

        // 修复：正确设置LUT文件名
        val lutFile = File(lutFilePath)
        currentLutName = lutFile.nameWithoutExtension

        // 设置第二个LUT文件名，并检查文件是否存在
        val hasSecondLut = if (lut2FilePath.isNotEmpty()) {
            val lut2File = File(lut2FilePath)
            if (lut2File.exists()) {
                currentLut2Name = lut2File.nameWithoutExtension
                true
            } else {
                currentLut2Name = ""
                Log.w(TAG, "第二个LUT文件不存在: $lut2FilePath")
                false
            }
        } else {
            currentLut2Name = ""
            false
        }

        // 修复：正确的抖动类型转换
        // 如果没有第二个LUT，强制将lut2Strength设置为0，避免使用之前加载的LUT
        this.processingParams = ILutProcessor.ProcessingParams(
            strength = strength / 100f,
            lut2Strength = if (hasSecondLut) lut2Strength / 100f else 0f,
            quality = quality,
            ditherType = when (dither.uppercase()) {
                "FLOYD_STEINBERG" -> ILutProcessor.DitherType.FLOYD_STEINBERG
                "RANDOM" -> ILutProcessor.DitherType.RANDOM
                "NONE" -> ILutProcessor.DitherType.NONE
                else -> ILutProcessor.DitherType.NONE
            }
        )

        isMonitoring = true
        monitoringJob = serviceScope.launch {
            try {
                Log.d(TAG, "开始监控文件夹: $inputFolder")

                // 加载主要LUT文件
                if (lutFile.exists()) {
                    threadManager.loadLut(lutFile.inputStream())
                    Log.d(TAG, "LUT文件加载成功: $currentLutName")
                } else {
                    Log.e(TAG, "LUT文件不存在: $lutFilePath")
                    return@launch
                }

                // 加载第二个LUT文件（如果提供）
                if (lut2FilePath.isNotEmpty()) {
                    val lut2File = File(lut2FilePath)
                    if (lut2File.exists()) {
                        threadManager.loadSecondLut(lut2File.inputStream())
                        Log.d(TAG, "第二个LUT文件加载成功: $currentLut2Name")
                    } else {
                        Log.w(TAG, "第二个LUT文件不存在: $lut2FilePath")
                    }
                }

                // 设置颗粒配置到处理器（GPU处理器会在同一pass中处理LUT+颗粒）
                if (preferencesManager.folderMonitorGrainEnabled) {
                    val grainConfig = preferencesManager.getFilmGrainConfig().copy(isEnabled = true)
                    threadManager.setFilmGrainConfig(grainConfig)
                    Log.d(TAG, "颗粒配置已设置到处理器: 强度=${grainConfig.globalStrength}")
                } else {
                    threadManager.setFilmGrainConfig(null)
                    Log.d(TAG, "颗粒效果已禁用")
                }

                isLutLoaded = true

                // 使用FileTracker进行监控（优化版本）
                startFileTrackerMonitoring(processNewFilesOnly)
            } catch (e: Exception) {
                Log.e(TAG, "监控过程中发生错误", e)
            }
        }
    }
    
    /**
     * 使用FileTracker进行文件监控（优化版本）
     * 替代原有的轮询扫描方式，解决ANR问题
     */
    private suspend fun startFileTrackerMonitoring(processNewFilesOnly: Boolean) {
        // 初始化FileTrackerManager，传递文件数量检测回调
        fileTrackerManager = FileTrackerManager(this) { fileCount ->
            // 文件数量检测回调：如果超过1000个，发送警告广播
            if (fileCount > 1000) {
                Log.w(TAG, "检测到输入文件夹内文件数量过多: $fileCount 个")
                val warningIntent = android.content.Intent("cn.alittlecookie.lut2photo.FILE_COUNT_WARNING").apply {
                    setPackage(packageName)
                    putExtra("file_count", fileCount)
                }
                sendBroadcast(warningIntent)
            }
        }
        
        val config = FileTrackerConfig(
            targetFolderUri = inputFolderUri,
            allowedExtensions = setOf("jpg", "jpeg", "png", "webp"),
            batchSize = 500
        )
        
        // 显示监控开始通知
        val notification = createNotification("文件夹监控服务", "正在扫描现有文件...")
        startForeground(NOTIFICATION_ID, notification)
        broadcastMonitoringStatus("正在扫描现有文件...")
        
        try {
            // 启动FileTracker
            fileTrackerManager!!.start(config)
            
            // 等待冷扫描完成
            fileTrackerManager!!.awaitColdScanComplete()
            
            val existingCount = fileTrackerManager!!.getExistingFilesCount()
            Log.d(TAG, "冷扫描完成，存量文件: ${existingCount}个")
            
            // 处理存量文件（如果需要）
            if (!processNewFilesOnly) {
                val existingFiles = fileTrackerManager!!.consumeExisting()
                Log.d(TAG, "开始处理存量文件: ${existingFiles.size}个")
                
                val initialStatus = "正在处理存量文件: 0/${existingFiles.size}"
                val statusNotification = createNotification(
                    "文件夹监控服务",
                    initialStatus
                )
                startForeground(NOTIFICATION_ID, statusNotification)
                broadcastMonitoringStatus(initialStatus)
                
                for ((index, fileRecord) in existingFiles.withIndex()) {
                    if (!isMonitoring) break
                    processFileRecord(fileRecord)
                    
                    // 每处理10个文件更新一次通知
                    if (index % 10 == 0) {
                        val progressStatus = "正在处理存量文件: ${index + 1}/${existingFiles.size}"
                        val progressNotification = createNotification(
                            "文件夹监控服务",
                            progressStatus
                        )
                        startForeground(NOTIFICATION_ID, progressNotification)
                        broadcastMonitoringStatus(progressStatus)
                    }
                }
            } else {
                // 仅处理新增文件模式：将存量文件标记为已处理
                Log.d(TAG, "仅处理新增文件模式：开始标记存量文件")
                val existingFiles = fileTrackerManager!!.consumeExisting()
                Log.d(TAG, "存量文件数量: ${existingFiles.size}个，将标记为SKIPPED状态")
                
                // 异步标记存量文件，避免阻塞监控流程
                serviceScope.launch {
                    markExistingFilesInHistory(existingFiles)
                }
            }
            
            // 更新通知为监控状态
            val monitoringNotification = createNotification(
                "文件夹监控服务",
                "监控中... 已处理 $processedCount 个文件"
            )
            startForeground(NOTIFICATION_ID, monitoringNotification)
            broadcastMonitoringStatus("监控中... 已处理 $processedCount 个文件")
            
            // 监听增量文件
            Log.d(TAG, "开始监听增量文件")
            fileTrackerManager!!.consumeIncremental().collect { fileRecord ->
                if (!isMonitoring) return@collect
                
                Log.d(TAG, "检测到增量文件: ${fileRecord.fileName}")
                processFileRecord(fileRecord)
                
                // 更新通知
                val updateNotification = createNotification(
                    "文件夹监控服务",
                    "已处理 $processedCount 个文件，监控中..."
                )
                startForeground(NOTIFICATION_ID, updateNotification)
                broadcastMonitoringStatus("已处理 $processedCount 个文件，监控中...")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "FileTracker监控出错", e)
            // 降级到原有的轮询方式
            Log.w(TAG, "降级到轮询监控模式")
            startContinuousMonitoring()
        }
    }
    
    /**
     * 处理FileRecord
     */
    private suspend fun processFileRecord(fileRecord: FileRecord) {
        try {
            Log.d(TAG, "开始处理文件: ${fileRecord.fileName}")
            
            // 检查文件是否已处理（统一使用处理历史系统）
            if (isFileAlreadyProcessed(fileRecord.fileName)) {
                Log.d(TAG, "文件已处理，跳过: ${fileRecord.fileName}")
                fileTrackerManager?.markFileAsProcessed(fileRecord.fileName)
                return
            }
            
            val documentFile = DocumentFile.fromSingleUri(this, fileRecord.uri)
            if (documentFile == null || !documentFile.exists()) {
                Log.w(TAG, "文件不存在或无法访问: ${fileRecord.fileName}")
                return
            }
            
            // 检查文件完整性（防止处理未完全传输的文件）
            if (!isImageFileComplete(documentFile)) {
                // 记录重试次数
                val retryCount = fileRetryCount.getOrDefault(fileRecord.fileName, 0) + 1
                fileRetryCount[fileRecord.fileName] = retryCount
                incompleteFiles.add(fileRecord.fileName)
                
                if (retryCount >= maxRetryCount) {
                    Log.w(TAG, "文件 ${fileRecord.fileName} 重试次数已达上限($maxRetryCount)，跳过处理")
                    // 标记为已处理，避免无限重试
                    fileTrackerManager?.markFileAsProcessed(fileRecord.fileName)
                    fileRetryCount.remove(fileRecord.fileName)
                    incompleteFiles.remove(fileRecord.fileName)
                } else {
                    Log.d(TAG, "文件 ${fileRecord.fileName} 未完整传输，等待下次检测 (重试次数: $retryCount/$maxRetryCount)")
                    // 不标记为已处理，等待下次增量检测时重新尝试
                }
                return
            }
            
            // 文件完整性校验通过，清理重试记录
            Log.d(TAG, "文件完整性校验通过: ${fileRecord.fileName}")
            fileRetryCount.remove(fileRecord.fileName)
            incompleteFiles.remove(fileRecord.fileName)
            
            startProcessingFile(fileRecord.fileName)
            
            processingParams?.let { params ->
                Log.d(TAG, "处理文件 ${fileRecord.fileName} 使用的参数: strength=${params.strength}, lut2Strength=${params.lut2Strength}, quality=${params.quality}, dither=${params.ditherType}")
                processDocumentFile(documentFile, getOutputDir()!!, params)
            }
            
            completeProcessingFile(fileRecord.fileName)
            fileTrackerManager?.markFileAsProcessed(fileRecord.fileName)
            
        } catch (e: Exception) {
            Log.e(TAG, "处理文件失败: ${fileRecord.fileName}", e)
            completeProcessingFile(fileRecord.fileName)
        }
    }

    // 添加历史记录管理
    /**
     * 检查文件是否已处理（带缓存优化）
     * 优化：使用 ConcurrentHashMap 缓存查询结果，避免重复查询 SharedPreferences
     */
    private fun isFileAlreadyProcessed(fileName: String): Boolean {
        // 先查缓存
        processedFilesCache[fileName]?.let { return it }
        
        // 缓存未命中，查询 SharedPreferences
        val prefs = getSharedPreferences("processing_history", MODE_PRIVATE)
        val existingRecords = prefs.getStringSet("records", emptySet()) ?: emptySet()

        val result = existingRecords.any { recordStr ->
            try {
                val parts = recordStr.split("|", limit = 6)
                if (parts.size >= 2 && parts[1] == fileName) {
                    // 如果是"仅处理新增文件"模式，所有记录（包括SKIPPED）都算已处理
                    // 如果不是"仅处理新增文件"模式，只有非SKIPPED状态的记录才算已处理
                    if (processNewFilesOnly) {
                        true
                    } else {
                        // 检查状态字段（如果存在）
                        if (parts.size >= 5) {
                            parts[4] != "SKIPPED"
                        } else {
                            // 旧格式没有状态字段，默认为已处理
                            true
                        }
                    }
                } else {
                    false
                }
            } catch (_: Exception) {
                false
            }
        }
        
        // 缓存结果
        processedFilesCache[fileName] = result
        return result
    }
    
    /**
     * 清空处理状态缓存
     * 在监控停止或配置变更时调用
     */
    private fun clearProcessedFilesCache() {
        processedFilesCache.clear()
        Log.d(TAG, "处理状态缓存已清空")
    }

    /**
     * 标记现有文件为已处理（用于"仅处理新增文件"功能）
     * 将FileTracker识别的存量文件同步到处理历史系统中
     * 优化版：使用异步处理，不阻塞服务启动
     * @param inputFolderUri 输入文件夹URI
     */
    private fun markExistingFilesAsProcessed(inputFolderUri: String) {
        serviceScope.launch {
            try {
                Log.d(TAG, "开始标记存量文件为已处理（异步）")
                
                // 等待FileTracker冷扫描完成
                fileTrackerManager?.awaitColdScanComplete()
                
                // 获取存量文件列表
                val existingFiles = fileTrackerManager?.consumeExisting() ?: emptyList()
                Log.d(TAG, "FileTracker识别的存量文件: ${existingFiles.size}个")
                
                if (existingFiles.isNotEmpty()) {
                    // 调用优化后的标记方法
                    markExistingFilesInHistory(existingFiles)
                } else {
                    Log.d(TAG, "没有存量文件需要标记")
                }
                
                Log.d(TAG, "存量文件标记流程完成")
                
            } catch (e: Exception) {
                Log.e(TAG, "标记存量文件失败", e)
            }
        }
    }
    
    /**
     * 将存量文件标记到处理历史中（用于"仅处理新增文件"模式）
     * 优化版：使用 HashSet 索引，时间复杂度从 O(n²) 降到 O(n)
     */
    private suspend fun markExistingFilesInHistory(existingFiles: List<FileRecord>) {
        val startTime = System.currentTimeMillis()
        
        try {
            Log.d(TAG, "开始标记 ${existingFiles.size} 个存量文件")
            
            val prefs = getSharedPreferences("processing_history", MODE_PRIVATE)
            val existingRecords = prefs.getStringSet("records", emptySet())?.toMutableSet() 
                ?: mutableSetOf()
            
            // 优化1：构建文件名索引 - O(n)，避免重复遍历
            val processedFileNames = existingRecords.mapNotNull { recordStr ->
                try {
                    // 使用 limit 参数优化 split 性能，只分割前3个字段
                    recordStr.split("|", limit = 3).getOrNull(1)
                } catch (e: Exception) {
                    null
                }
            }.toHashSet()
            
            Log.d(TAG, "已处理文件索引构建完成: ${processedFileNames.size} 个")
            
            // 优化2：快速过滤需要添加的文件 - O(n)，HashSet 查找是 O(1)
            val filesToAdd = existingFiles.filter { it.fileName !in processedFileNames }
            
            if (filesToAdd.isEmpty()) {
                Log.d(TAG, "所有存量文件都已在处理历史中")
                return
            }
            
            Log.d(TAG, "需要标记 ${filesToAdd.size} 个新文件")
            
            // 优化3：分批处理，避免内存峰值和ANR
            val batchSize = 1000
            val totalBatches = (filesToAdd.size + batchSize - 1) / batchSize
            
            filesToAdd.chunked(batchSize).forEachIndexed { batchIndex, batch ->
                // 优化4：批量构建记录字符串，使用 StringBuilder 预分配容量
                val timestamp = System.currentTimeMillis()
                val batchRecords = batch.map { fileRecord ->
                    // 格式：timestamp|fileName|inputPath|outputPath|status|lutFileName|lut2FileName|strength|lut2Strength|quality|ditherType
                    StringBuilder(256).apply {
                        append(timestamp).append('|')
                        append(fileRecord.fileName).append('|')
                        append(fileRecord.uri).append('|')  // inputPath
                        append('|')  // outputPath (空)
                        append("SKIPPED").append('|')  // status
                        append(currentLutName).append('|')  // lutFileName
                        append(currentLut2Name).append('|')  // lut2FileName
                        append(processingParams?.strength ?: 0.0f).append('|')  // strength
                        append(processingParams?.lut2Strength ?: 0.0f).append('|')  // lut2Strength
                        append(processingParams?.quality ?: 0).append('|')  // quality
                        append(processingParams?.ditherType?.name ?: "NONE")  // ditherType
                    }.toString()
                }
                
                existingRecords.addAll(batchRecords)
                
                // 优化5：每批写入一次，使用 commit() 确保完成
                prefs.edit().putStringSet("records", existingRecords).commit()
                
                // 批量更新缓存
                batch.forEach { fileRecord ->
                    processedFilesCache[fileRecord.fileName] = true
                }
                
                val processed = minOf((batchIndex + 1) * batchSize, filesToAdd.size)
                Log.d(TAG, "批次 ${batchIndex + 1}/$totalBatches: 已标记 $processed/${filesToAdd.size}")
                
                // 更新通知，显示进度
                val notification = createNotification(
                    "正在标记存量文件",
                    "已标记 $processed / ${filesToAdd.size}",
                    processed,
                    filesToAdd.size
                )
                startForeground(NOTIFICATION_ID, notification)
                broadcastMonitoringStatus("正在标记存量文件: $processed / ${filesToAdd.size}")
                
                // 让出CPU时间，避免ANR
                if (batchIndex < totalBatches - 1) {
                    delay(50)
                }
            }
            
            val duration = System.currentTimeMillis() - startTime
            val avgTime = if (filesToAdd.isNotEmpty()) duration.toFloat() / filesToAdd.size else 0f
            Log.d(TAG, "标记完成，耗时: ${duration}ms，平均: ${"%.2f".format(avgTime)}ms/文件")
            
        } catch (e: Exception) {
            Log.e(TAG, "标记存量文件到处理历史失败", e)
        }
    }

    /**
     * 重新加载LUT配置（用于实时切换）
     */
    private suspend fun reloadLutConfiguration() {
        try {
            Log.d(TAG, "========== 开始重新加载LUT配置 ==========")

            // 从偏好设置获取最新的LUT配置（文件名）
            val lutFileName = preferencesManager.homeLutUri
            val lut2FileName = preferencesManager.homeLut2Uri ?: ""
            val strength = preferencesManager.homeStrength.toInt()
            val lut2Strength = preferencesManager.homeLut2Strength.toInt()
            val quality = preferencesManager.homeQuality.toInt()
            val ditherType = preferencesManager.homeDitherType

            Log.d(TAG, "从SharedPreferences读取的配置:")
            Log.d(TAG, "  LUT1: $lutFileName")
            Log.d(TAG, "  LUT2: $lut2FileName")
            Log.d(TAG, "  强度: $strength%")
            Log.d(TAG, "  LUT2强度: $lut2Strength%")
            Log.d(TAG, "  质量: $quality")
            Log.d(TAG, "  抖动: $ditherType")

            if (lutFileName.isNullOrEmpty()) {
                Log.w(TAG, "主要LUT文件路径为空，不能重新加载")
                return
            }

            // 获取LUT文件的完整路径
            val lutDirectory = File(getExternalFilesDir(null), "android_data/luts")
            val lutFilePath = File(lutDirectory, lutFileName).absolutePath
            val lut2FilePath = if (lut2FileName.isNotEmpty()) {
                File(lutDirectory, lut2FileName).absolutePath
            } else {
                ""
            }

            Log.d(TAG, "转换为完整路径:")
            Log.d(TAG, "  LUT1完整路径: $lutFilePath")
            Log.d(TAG, "  LUT2完整路径: $lut2FilePath")

            // 更新内部状态
            this.lutFilePath = lutFilePath
            this.lut2FilePath = lut2FilePath

            // 加载主要LUT文件
            val lutFile = File(lutFilePath)
            if (lutFile.exists()) {
                threadManager.loadLut(lutFile.inputStream())
                currentLutName = lutFile.nameWithoutExtension
                Log.d(TAG, "重新加载主LUT成功: $currentLutName")
            } else {
                Log.e(TAG, "主LUT文件不存在: $lutFilePath")
                return
            }

            // 加载第二个LUT文件（如果提供）
            val hasSecondLut = if (lut2FilePath.isNotEmpty()) {
                val lut2File = File(lut2FilePath)
                if (lut2File.exists()) {
                    threadManager.loadSecondLut(lut2File.inputStream())
                    currentLut2Name = lut2File.nameWithoutExtension
                    Log.d(TAG, "重新加载第二个LUT成功: $currentLut2Name")
                    true
                } else {
                    Log.w(TAG, "第二个LUT文件不存在: $lut2FilePath")
                    currentLut2Name = ""
                    false
                }
            } else {
                currentLut2Name = ""
                Log.d(TAG, "没有配置第二个LUT文件")
                false
            }

            // 更新处理参数
            // 如果没有第二个LUT，强制将lut2Strength设置为0，避免使用之前加载的LUT
            val finalLut2Strength = if (hasSecondLut) lut2Strength / 100f else 0f
            
            this.processingParams = ILutProcessor.ProcessingParams(
                strength = strength / 100f,
                lut2Strength = finalLut2Strength,
                quality = quality,
                ditherType = when (ditherType.uppercase()) {
                    "FLOYD_STEINBERG" -> ILutProcessor.DitherType.FLOYD_STEINBERG
                    "RANDOM" -> ILutProcessor.DitherType.RANDOM
                    "NONE" -> ILutProcessor.DitherType.NONE
                    else -> ILutProcessor.DitherType.NONE
                }
            )

            Log.d(TAG, "处理参数已更新:")
            Log.d(TAG, "  strength=${this.processingParams?.strength}")
            Log.d(TAG, "  lut2Strength=${this.processingParams?.lut2Strength}")
            Log.d(TAG, "  quality=${this.processingParams?.quality}")
            Log.d(TAG, "  ditherType=${this.processingParams?.ditherType}")

            // 更新通知
            val lutDisplayName = if (currentLut2Name.isNotEmpty()) {
                "$currentLutName + $currentLut2Name"
            } else {
                currentLutName
            }

            // 使用临时状态广播，不更新 currentStatusMessage
            val configStatus = "配置已更新: $lutDisplayName (强度:${strength}% 质量:${quality})"
            val notification = createNotification(
                "文件夹监控服务",
                configStatus
            )
            startForeground(NOTIFICATION_ID, notification)
            
            // 发送临时状态，3秒后自动恢复到持久状态
            broadcastTemporaryStatus(configStatus, autoRestoreDelayMs = 3000)

            Log.d(TAG, "========== LUT配置重新加载完成 ==========")

        } catch (e: Exception) {
            Log.e(TAG, "LUT配置重新加载失败", e)
        }
    }

    private fun saveProcessingRecord(
        fileName: String,
        inputPath: String,
        outputPath: String,
        lutFileName: String,
        params: ILutProcessor.ProcessingParams
    ) {
        val timestamp = System.currentTimeMillis()
        
        Log.d(TAG, "========== 保存处理记录 ==========")
        Log.d(TAG, "文件名: $fileName")
        Log.d(TAG, "LUT1: $lutFileName, 强度: ${params.strength}")
        Log.d(TAG, "LUT2: $currentLut2Name, 强度: ${params.lut2Strength}")
        Log.d(TAG, "质量: ${params.quality}, 抖动: ${params.ditherType.name}")
        
        // 新格式：timestamp|fileName|inputPath|outputPath|status|lutFileName|lut2FileName|strength|lut2Strength|quality|ditherType
        val recordString = buildString {
            append(timestamp)
            append("|")
            append(fileName)
            append("|")
            append(inputPath)
            append("|")
            append(outputPath)
            append("|")
            append("处理完成")
            append("|")
            append(lutFileName)
            append("|")
            append(currentLut2Name) // 第二个LUT名称
            append("|")
            append(params.strength)
            append("|")
            append(params.lut2Strength) // 第二个LUT强度
            append("|")
            append(params.quality)
            append("|")
            append(params.ditherType.name)
        }
        
        Log.d(TAG, "记录字符串: $recordString")

        val prefs = getSharedPreferences("processing_history", MODE_PRIVATE)
        val existingRecords =
            prefs.getStringSet("records", emptySet())?.toMutableSet() ?: mutableSetOf()
        existingRecords.add(recordString)

        // 限制记录数量
        if (existingRecords.size > 10000) {
            val sortedRecords = existingRecords.mapNotNull { recordStr ->
                try {
                    val parts = recordStr.split("|")
                    parts[0].toLong() to recordStr
                } catch (_: Exception) {
                    null
                }
            }.sortedByDescending { it.first }

            existingRecords.clear()
            existingRecords.addAll(sortedRecords.take(10000).map { it.second })
        }

        prefs.edit { putStringSet("records", existingRecords) }
        
        // 更新缓存
        processedFilesCache[fileName] = true

        // 发送广播通知历史页面更新
        val intent = Intent("cn.alittlecookie.lut2photo.PROCESSING_UPDATE")
        sendBroadcast(intent)
    }

    /**
     * 检查图片文件完整性
     * @param file 要检查的图片文件
     * @return true表示图片完整，false表示不完整
     */
    private suspend fun isImageFileComplete(file: DocumentFile): Boolean {
        return try {
            val inputStream = contentResolver.openInputStream(file.uri)
            if (inputStream == null) {
                Log.w(TAG, "无法打开文件流: ${file.name}")
                return false
            }
            
            inputStream.use { stream ->
                // 方法1: 尝试解码图片
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true // 只获取图片信息，不加载到内存
                }
                
                BitmapFactory.decodeStream(stream, null, options)
                
                // 检查是否成功获取图片尺寸信息
                if (options.outWidth <= 0 || options.outHeight <= 0) {
                    Log.w(TAG, "图片尺寸信息无效: ${file.name}, width=${options.outWidth}, height=${options.outHeight}")
                    return false
                }
                
                // 方法2: 检查文件大小是否稳定
                val fileSize = file.length()
                if (fileSize <= 0) {
                    Log.w(TAG, "文件大小无效: ${file.name}, size=$fileSize")
                    return false
                }
                
                // 等待一小段时间后再次检查文件大小
                kotlinx.coroutines.delay(500)
                val newFileSize = file.length()
                if (fileSize != newFileSize) {
                    Log.w(TAG, "文件大小不稳定，可能仍在写入: ${file.name}, 之前=$fileSize, 现在=$newFileSize")
                    return false
                }
                
                Log.d(TAG, "图片完整性校验通过: ${file.name}, size=$fileSize, ${options.outWidth}x${options.outHeight}")
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "图片完整性校验失败: ${file.name}", e)
            false
        }
    }

    private suspend fun startContinuousMonitoring() {
        val inputUri = inputFolderUri.toUri()
        val outputUri = outputFolderUri.toUri()

        val inputDir = DocumentFile.fromTreeUri(this, inputUri)
        val outputDir = DocumentFile.fromTreeUri(this, outputUri)

        if (inputDir == null || outputDir == null) {
            Log.e(TAG, "无法访问文件夹")
            return
        }

        // 立即显示监控开始通知
        val initialStatus = "监控已启动，等待新文件..."
        val notification = createNotification("文件夹监控服务", initialStatus)
        startForeground(NOTIFICATION_ID, notification)
        broadcastMonitoringStatus(initialStatus)

        var scanCount = 0
        var lastHeartbeat = System.currentTimeMillis()

        while (serviceScope.isActive && isMonitoring) {
            try {
                scanCount++
                val currentTime = System.currentTimeMillis()

                // 每30秒更新一次心跳通知
                if (currentTime - lastHeartbeat > 30000) {
                    val heartbeatStatus = "监控中... 已处理 $processedCount 个文件 (扫描次数: $scanCount)"
                    val statusNotification = createNotification(
                        "文件夹监控服务",
                        heartbeatStatus
                    )
                    startForeground(NOTIFICATION_ID, statusNotification)
                    broadcastMonitoringStatus(heartbeatStatus)
                    lastHeartbeat = currentTime

                    // 重新获取WakeLock
                    wakeLock?.takeIf { !it.isHeld }?.acquire(10 * 60 * 1000L)
                }

                // 扫描输入文件夹中的新图片
                val imageFiles = inputDir.listFiles().filter { file ->
                    file.isFile &&
                            file.name?.lowercase()?.let { name ->
                                name.endsWith(".jpg") || name.endsWith(".jpeg") ||
                                        name.endsWith(".png") || name.endsWith(".webp")
                            } == true &&
                            !isFileAlreadyProcessed(file.name ?: "") &&
                            !processingFiles.contains(file.name)
                }

                // 处理新发现的图片
                for (imageFile in imageFiles) {
                    if (!isMonitoring) break

                    imageFile.name?.let { fileName ->
                        // 检查图片完整性
                        if (isImageFileComplete(imageFile)) {
                            // 图片完整，可以处理
                            Log.d(TAG, "图片完整性校验通过，开始处理: $fileName")
                            
                            // 清理重试记录
                            incompleteFiles.remove(fileName)
                            fileRetryCount.remove(fileName)
                            
                            startProcessingFile(fileName)

                            try {
                                processingParams?.let { params ->
                                    Log.d(TAG, "处理文件 $fileName 使用的参数: strength=${params.strength}, lut2Strength=${params.lut2Strength}, quality=${params.quality}, dither=${params.ditherType}")
                                    processDocumentFile(imageFile, outputDir, params)
                                }
                                completeProcessingFile(fileName)
                            } catch (e: Exception) {
                                Log.e(TAG, "处理文件失败: $fileName", e)
                                completeProcessingFile(fileName)
                            }
                        } else {
                            // 图片不完整，记录并跳过
                            val retryCount = fileRetryCount.getOrDefault(fileName, 0) + 1
                            fileRetryCount[fileName] = retryCount
                            incompleteFiles.add(fileName)
                            
                            if (retryCount >= maxRetryCount) {
                                Log.w(TAG, "图片 $fileName 重试次数已达上限($maxRetryCount)，跳过处理")
                                // 将其标记为已处理，避免无限重试
                                completedFiles.add(fileName)
                                fileRetryCount.remove(fileName)
                                incompleteFiles.remove(fileName)
                            } else {
                                Log.d(TAG, "图片 $fileName 未完整加载，跳过本次处理 (重试次数: $retryCount/$maxRetryCount)")
                            }
                        }
                    }
                }

                // 等待一段时间再次扫描
                kotlinx.coroutines.delay(2000)
            } catch (e: Exception) {
                Log.e(TAG, "监控循环中发生错误", e)
                // 不要立即退出，尝试继续监控
                kotlinx.coroutines.delay(5000)
            }
        }
    }

    private suspend fun processDocumentFile(
        inputFile: DocumentFile,
        outputDir: DocumentFile,
        params: ILutProcessor.ProcessingParams
    ) {
        try {
            Log.d(TAG, "开始处理文件: ${inputFile.name}")

            val inputStream = contentResolver.openInputStream(inputFile.uri)
            if (inputStream != null) {
                // 首先读取EXIF信息
                val exif = ExifInterface(inputStream)
                val orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
                inputStream.close()

                // 重新打开流来解码图像
                val decodeStream = contentResolver.openInputStream(inputFile.uri)
                if (decodeStream != null) {
                    val bitmap = BitmapFactory.decodeStream(decodeStream)
                    decodeStream.close()

                    if (bitmap != null) {
                        // 应用EXIF方向变换
                        val correctedBitmap = applyExifOrientation(bitmap, orientation)

                        // 使用submitTask处理图片（LUT处理）
                        val lutProcessedBitmap = suspendCoroutine { continuation ->
                            threadManager.submitTask(
                                bitmap = correctedBitmap,
                                params = params,
                                onComplete = { result ->
                                    continuation.resume(result.getOrNull())
                                }
                            )
                        }

                        if (lutProcessedBitmap != null) {
                            // 检查是否需要添加水印
                            val watermarkConfig =
                                preferencesManager.getWatermarkConfig(forFolderMonitor = true)  // 明确指定是文件夹监控
                            var processedBitmap =
                                if (watermarkConfig.isEnabled) {  // 这里会使用folderMonitorWatermarkEnabled
                                    Log.d(TAG, "开始添加水印: ${inputFile.name}")
                                    try {
                                        val watermarkedBitmap = watermarkProcessor.addWatermark(
                                            lutProcessedBitmap,
                                            watermarkConfig,
                                            inputFile.uri,
                                            currentLutName,
                                            if (currentLut2Name.isNotEmpty()) currentLut2Name else null,
                                            params.strength,
                                            params.lut2Strength
                                        )
                                        Log.d(TAG, "水印添加完成: ${inputFile.name}")
                                        watermarkedBitmap
                                    } catch (e: Exception) {
                                        Log.e(TAG, "添加水印失败: ${inputFile.name}", e)
                                        lutProcessedBitmap
                                    }
                                } else {
                                    lutProcessedBitmap
                                }

                            // 颗粒效果处理逻辑：
                            // - GPU处理器：颗粒已在着色器中与LUT一起处理，无需单独处理
                            // - CPU处理器：需要单独调用FilmGrainProcessor处理颗粒
                            val processorInfo = threadManager.getProcessorInfo()
                            val usedGpu = processorInfo.preferredProcessor == ILutProcessor.ProcessorType.GPU && processorInfo.isGpuAvailable
                            
                            val finalBitmap = if (preferencesManager.folderMonitorGrainEnabled && !usedGpu) {
                                // CPU处理器：需要单独处理颗粒
                                Log.d(TAG, "CPU处理器：开始单独添加颗粒效果: ${inputFile.name}")
                                try {
                                    val grainConfig = preferencesManager.getFilmGrainConfig().copy(isEnabled = true)
                                    val grainProcessor = cn.alittlecookie.lut2photo.lut2photo.core.FilmGrainProcessor()
                                    val grainedBitmap = grainProcessor.processImage(processedBitmap, grainConfig)
                                    if (grainedBitmap != null) {
                                        Log.d(TAG, "CPU颗粒效果添加完成: ${inputFile.name}")
                                        grainedBitmap
                                    } else {
                                        Log.w(TAG, "CPU颗粒效果添加失败，使用原图: ${inputFile.name}")
                                        processedBitmap
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "CPU添加颗粒效果失败: ${inputFile.name}", e)
                                    processedBitmap
                                }
                            } else if (preferencesManager.folderMonitorGrainEnabled && usedGpu) {
                                Log.d(TAG, "GPU处理器：颗粒已在着色器中处理，跳过单独处理: ${inputFile.name}")
                                processedBitmap
                            } else {
                                processedBitmap
                            }

                            // 修复：使用正确的文件命名格式
                            val originalName = inputFile.name?.substringBeforeLast(".") ?: "unknown"
                            val outputFileName = "${originalName}-${currentLutName}.jpg"
                            val outputFile = outputDir.createFile("image/jpeg", outputFileName)

                            // 在processDocumentFile方法中，替换原来的保存逻辑
                            outputFile?.let { file ->
                                // 直接保存带EXIF信息的图片，而不是分两步
                                saveBitmapWithExif(
                                    finalBitmap,
                                    inputFile.uri,
                                    file.uri,
                                    params.quality,
                                    outputFileName
                                )
                                
                                // 保存处理记录到历史
                                saveProcessingRecord(
                                    fileName = inputFile.name ?: "unknown",
                                    inputPath = inputFile.uri.toString(),
                                    outputPath = file.uri.toString(),
                                    lutFileName = currentLutName,
                                    params = params
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理文档文件失败: ${inputFile.name}", e)
            throw e
        }
    }

    private fun startProcessingFile(fileName: String) {
        processingFiles.add(fileName)
        val status = "处理中: $fileName"
        val notification = createNotification(
            "正在处理文件",
            status,
            processedCount,
            processedCount + processingFiles.size
        )
        startForeground(NOTIFICATION_ID, notification)
        // 同步更新状态消息
        currentStatusMessage = status
    }

    private fun completeProcessingFile(fileName: String) {
        processingFiles.remove(fileName)
        completedFiles.add(fileName)
        processedCount++
        val status = "已处理 $processedCount 个文件，监控中..."
        val notification = createNotification(
            "文件夹监控服务",
            status,
            processedCount,
            processedCount + processingFiles.size
        )
        startForeground(NOTIFICATION_ID, notification)
        broadcastMonitoringStatus(status)
    }

    // 在服务停止时清理状态
    private fun stopMonitoring() {
        isMonitoring = false
        monitoringJob?.cancel()
        monitoringJob = null
        
        // 停止FileTracker
        fileTrackerManager?.stop()
        fileTrackerManager = null
        outputDir = null
        
        // 清空处理状态缓存
        clearProcessedFilesCache()

        // 修复：清理状态，表示服务已停止
        val preferencesManager = PreferencesManager(this)
        preferencesManager.isMonitoring = false
        preferencesManager.monitoringSwitchEnabled = false

        // 重置状态消息
        currentStatusMessage = "监控已停止"
        
        val notification = createNotification("监控已停止")
        startForeground(NOTIFICATION_ID, notification)
        broadcastMonitoringStatus("监控已停止")

        Log.d(TAG, "文件夹监控已停止，所有状态已清理")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(configChangeReceiver)
            Log.d(TAG, "Config change receiver unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister config change receiver", e)
        }

        // 修复：只有在监控已停止的情况下才清理UI状态
        // 如果服务是被系统杀死的，不应该清理UI状态，以便重启后恢复
        if (!isMonitoring) {
            val preferencesManager = PreferencesManager(this)
            preferencesManager.isMonitoring = false
            preferencesManager.monitoringSwitchEnabled = false
            Log.d(TAG, "监控已停止，清理UI状态")
        } else {
            Log.d(TAG, "监控仍在运行，保留UI状态以便恢复")
        }

        monitoringJob?.cancel()
        
        // 释放FileTracker资源
        fileTrackerManager?.release()
        fileTrackerManager = null
        
        // 修复：在协程中异步释放资源，避免阻塞主线程导致ANR
        // 使用GlobalScope因为serviceScope可能已经被取消
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                threadManager.release()
                Log.d(TAG, "ThreadManager资源已释放")
            } catch (e: Exception) {
                Log.e(TAG, "释放ThreadManager资源失败", e)
            }
        }

        Log.d(TAG, "服务正在销毁")

        // 释放WakeLock
        wakeLock?.takeIf { it.isHeld }?.release()

        // 移除重启定时器
        restartHandler.removeCallbacks(restartRunnable)

        stopMonitoring()
        serviceScope.cancel()
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(-90f)
                matrix.postScale(-1f, 1f)
            }

            else -> return bitmap // ORIENTATION_NORMAL 或未知方向
        }

        return try {
            val rotatedBitmap = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )
            if (rotatedBitmap != bitmap) {
                bitmap.recycle()
            }
            rotatedBitmap
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "内存不足，无法旋转图像", e)
            bitmap
        }
    }

    private suspend fun saveBitmapWithExif(
        bitmap: Bitmap,
        sourceUri: Uri,
        targetUri: Uri,
        quality: Int,
        fileName: String = "image.jpg"
    ) {
        try {
            // **新增：在保存前压缩图片防止OOM**
            val compressedBitmap = threadManager.compressBitmapForSaving(
                bitmap = bitmap,
                fileName = fileName,
                onCompressed = { compressedFileName, newWidth, newHeight ->
                    // 在主线程显示Toast
                    serviceScope.launch {
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(
                                this@FolderMonitorService,
                                "${compressedFileName}尺寸过大，已压缩至${newWidth}×${newHeight}",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            )
            Log.d(
                TAG,
                "图片压缩完成，将保存尺寸: ${compressedBitmap.width}x${compressedBitmap.height}"
            )
            
            // 读取原始图片的EXIF信息
            val sourceExif = contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                ExifInterface(inputStream)
            }

            // 创建临时文件来保存带EXIF的图片
            val tempFile = File(cacheDir, "temp_with_exif_${System.currentTimeMillis()}.jpg")

            // 先将压缩后bitmap保存到临时文件
            tempFile.outputStream().use { outputStream ->
                compressedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            }

            // 如果有EXIF信息，则添加到临时文件
            sourceExif?.let { exif ->
                val targetExif = ExifInterface(tempFile.absolutePath)

                // 复制所有重要的EXIF标签
                val exifTags = arrayOf(
                    ExifInterface.TAG_DATETIME,
                    ExifInterface.TAG_DATETIME_DIGITIZED,
                    ExifInterface.TAG_DATETIME_ORIGINAL,
                    ExifInterface.TAG_MAKE,
                    ExifInterface.TAG_MODEL,
                    ExifInterface.TAG_APERTURE_VALUE,
                    ExifInterface.TAG_EXPOSURE_TIME,
                    ExifInterface.TAG_FOCAL_LENGTH,
                    ExifInterface.TAG_ISO_SPEED_RATINGS,
                    ExifInterface.TAG_WHITE_BALANCE,
                    ExifInterface.TAG_FLASH,
                    ExifInterface.TAG_GPS_LATITUDE,
                    ExifInterface.TAG_GPS_LONGITUDE,
                    ExifInterface.TAG_GPS_LATITUDE_REF,
                    ExifInterface.TAG_GPS_LONGITUDE_REF,
                    ExifInterface.TAG_GPS_ALTITUDE,
                    ExifInterface.TAG_GPS_ALTITUDE_REF,
                    ExifInterface.TAG_GPS_TIMESTAMP,
                    ExifInterface.TAG_GPS_DATESTAMP,
                    ExifInterface.TAG_LENS_MAKE,
                    ExifInterface.TAG_LENS_MODEL,
                    ExifInterface.TAG_F_NUMBER,
                    ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY
                )

                for (tag in exifTags) {
                    val value = exif.getAttribute(tag)
                    if (value != null) {
                        targetExif.setAttribute(tag, value)
                    }
                }

                // **修复：重置方向标签为NORMAL，因为图片已经旋转到正确方向**
                targetExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())

                // 保存EXIF信息到临时文件
                targetExif.saveAttributes()
            }

            // 将带有EXIF信息的临时文件复制到目标URI
            contentResolver.openOutputStream(targetUri)?.use { output ->
                tempFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            }

            // 清理临时文件
            tempFile.delete()

            // **清理资源：如果创建了新的压缩bitmap，则释放它**
            if (compressedBitmap != bitmap && !compressedBitmap.isRecycled) {
                compressedBitmap.recycle()
            }

            Log.d(TAG, "图片已保存并包含EXIF信息")
        } catch (e: Exception) {
            Log.e(TAG, "保存带EXIF信息的图片时发生错误", e)
            // 如果失败，则回退到普通保存（使用压缩后bitmap）
            try {
                val compressedBitmap = threadManager.compressBitmapForSaving(bitmap)
                contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                    compressedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
                }
                if (compressedBitmap != bitmap && !compressedBitmap.isRecycled) {
                    compressedBitmap.recycle()
                }
            } catch (fallbackException: Exception) {
                Log.e(TAG, "回退保存也失败", fallbackException)
            }
        }
    }

    private fun restartService() {
        val restartIntent = Intent(this, FolderMonitorService::class.java).apply {
            action = ACTION_START_MONITORING
            putExtra(EXTRA_INPUT_FOLDER, inputFolderUri)
            putExtra(EXTRA_OUTPUT_FOLDER, outputFolderUri)
            putExtra(EXTRA_LUT_FILE_PATH, lutFilePath)
            processingParams?.let { params ->
                putExtra(EXTRA_STRENGTH, (params.strength * 100).toInt())
                putExtra(EXTRA_QUALITY, params.quality)
                putExtra(EXTRA_DITHER, params.ditherType.name)
            }
        }

        try {
            startForegroundService(restartIntent)
        } catch (e: Exception) {
            Log.e(TAG, "重启服务失败", e)
        }
    }


}
