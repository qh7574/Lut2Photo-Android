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
import cn.alittlecookie.lut2photo.lut2photo.utils.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    }


    private var isLutLoaded = false
    private var isMonitoring = false
    private var monitoringJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var processedCount = 0
    private var currentLutName = ""

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

    private var wakeLock: PowerManager.WakeLock? = null
    private val restartHandler = Handler(Looper.getMainLooper())
    private val restartRunnable = Runnable {
        if (isMonitoring) {
            restartService()
        }
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
                    Log.d(TAG, "Received LUT config change, reloading LUT files")
                    serviceScope.launch {
                        reloadLutConfiguration()
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

        // 设置第二个LUT文件名
        currentLut2Name = if (lut2FilePath.isNotEmpty()) {
            val lut2File = File(lut2FilePath)
            lut2File.nameWithoutExtension
        } else {
            ""
        }

        // 修复：正确的抖动类型转换
        this.processingParams = ILutProcessor.ProcessingParams(
            strength = strength / 100f,
            lut2Strength = lut2Strength / 100f,
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

                isLutLoaded = true

                startContinuousMonitoring()
            } catch (e: Exception) {
                Log.e(TAG, "监控过程中发生错误", e)
            }
        }
    }

    // 添加历史记录管理
    private fun isFileAlreadyProcessed(fileName: String): Boolean {
        val prefs = getSharedPreferences("processing_history", MODE_PRIVATE)
        val existingRecords = prefs.getStringSet("records", emptySet()) ?: emptySet()

        return existingRecords.any { recordStr ->
            try {
                val parts = recordStr.split("|")
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
    }

    /**
     * 标记现有文件为已处理（用于"仅处理新增文件"功能）
     * @param inputFolderUri 输入文件夹URI
     */
    private fun markExistingFilesAsProcessed(inputFolderUri: String) {
        try {
            val inputUri = inputFolderUri.toUri()
            val inputDir = DocumentFile.fromTreeUri(this, inputUri)
            
            if (inputDir == null) {
                Log.e(TAG, "无法访问输入文件夹")
                return
            }
            
            // 扫描输入文件夹中的所有图片文件
            val imageFiles = inputDir.listFiles().filter { file ->
                file.isFile &&
                file.name?.lowercase()?.let { name ->
                    name.endsWith(".jpg") || name.endsWith(".jpeg") ||
                    name.endsWith(".png") || name.endsWith(".webp")
                } == true
            }
            
            val prefs = getSharedPreferences("processing_history", MODE_PRIVATE)
            val existingRecords = prefs.getStringSet("records", emptySet())?.toMutableSet() 
                ?: mutableSetOf()
            
            val timestamp = System.currentTimeMillis()
            var markedCount = 0
            
            for (imageFile in imageFiles) {
                val fileName = imageFile.name ?: continue
                
                // 检查是否已经在历史记录中
                val alreadyProcessed = existingRecords.any { recordStr ->
                    try {
                        val parts = recordStr.split("|")
                        parts.size >= 2 && parts[1] == fileName
                    } catch (_: Exception) {
                        false
                    }
                }
                
                if (!alreadyProcessed) {
                    // 创建一个标记记录（使用特殊状态标识，便于后续过滤）
                    val recordString = buildString {
                        append(timestamp)
                        append("|")
                        append(fileName)
                        append("|")
                        append(imageFile.uri.toString())
                        append("|")
                        append("") // 空输出路径
                        append("|")
                        append("SKIPPED") // 使用特殊状态标识，便于过滤
                        append("|")
                        append("") // 空LUT名称
                        append("|")
                        append("") // 空LUT2名称
                        append("|")
                        append("0") // 强度
                        append("|")
                        append("0") // LUT2强度
                        append("|")
                        append("0") // 质量
                        append("|")
                        append("NONE") // 抖动类型
                    }
                    
                    existingRecords.add(recordString)
                    markedCount++
                }
            }
            
            if (markedCount > 0) {
                prefs.edit { putStringSet("records", existingRecords) }
                Log.d(TAG, "已标记 $markedCount 个现有文件为已处理")
                
                // 发送广播通知历史页面更新
                val intent = Intent("cn.alittlecookie.lut2photo.PROCESSING_UPDATE")
                sendBroadcast(intent)
            } else {
                Log.d(TAG, "没有需要标记的新文件")
            }
        } catch (e: Exception) {
            Log.e(TAG, "标记现有文件失败", e)
        }
    }

    /**
     * 重新加载LUT配置（用于实时切换）
     */
    private suspend fun reloadLutConfiguration() {
        try {
            Log.d(TAG, "开始重新加载LUT配置")

            // 从偏好设置获取最新的LUT配置
            val lutFilePath = preferencesManager.homeLutUri
            val lut2FilePath = preferencesManager.homeLut2Uri ?: ""
            val strength = preferencesManager.homeStrength.toInt()
            val lut2Strength = preferencesManager.homeLut2Strength.toInt()
            val quality = preferencesManager.homeQuality.toInt()
            val ditherType = preferencesManager.homeDitherType

            if (lutFilePath.isNullOrEmpty()) {
                Log.w(TAG, "主要LUT文件路径为空，不能重新加载")
                return
            }

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
            if (lut2FilePath.isNotEmpty()) {
                val lut2File = File(lut2FilePath)
                if (lut2File.exists()) {
                    threadManager.loadSecondLut(lut2File.inputStream())
                    currentLut2Name = lut2File.nameWithoutExtension
                    Log.d(TAG, "重新加载第二个LUT成功: $currentLut2Name")
                } else {
                    Log.w(TAG, "第二个LUT文件不存在: $lut2FilePath")
                    currentLut2Name = ""
                }
            } else {
                currentLut2Name = ""
                Log.d(TAG, "没有配置第二个LUT文件")
            }

            // 更新处理参数
            this.processingParams = ILutProcessor.ProcessingParams(
                strength = strength / 100f,
                lut2Strength = lut2Strength / 100f,
                quality = quality,
                ditherType = when (ditherType.uppercase()) {
                    "FLOYD_STEINBERG" -> ILutProcessor.DitherType.FLOYD_STEINBERG
                    "RANDOM" -> ILutProcessor.DitherType.RANDOM
                    "NONE" -> ILutProcessor.DitherType.NONE
                    else -> ILutProcessor.DitherType.NONE
                }
            )

            // 更新通知
            val lutDisplayName = if (currentLut2Name.isNotEmpty()) {
                "$currentLutName + $currentLut2Name"
            } else {
                currentLutName
            }

            val notification = createNotification(
                "文件夹监控服务",
                "LUT配置已更新: $lutDisplayName"
            )
            startForeground(NOTIFICATION_ID, notification)

            Log.d(TAG, "LUT配置重新加载完成")

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
        val notification = createNotification("文件夹监控服务", "监控已启动，等待新文件...")
        startForeground(NOTIFICATION_ID, notification)

        var scanCount = 0
        var lastHeartbeat = System.currentTimeMillis()

        while (serviceScope.isActive && isMonitoring) {
            try {
                scanCount++
                val currentTime = System.currentTimeMillis()

                // 每30秒更新一次心跳通知
                if (currentTime - lastHeartbeat > 30000) {
                    val statusNotification = createNotification(
                        "文件夹监控服务",
                        "监控中... 已处理 $processedCount 个文件 (扫描次数: $scanCount)"
                    )
                    startForeground(NOTIFICATION_ID, statusNotification)
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
                            val finalBitmap =
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
        val notification = createNotification(
            "正在处理文件",
            "处理中: $fileName",
            processedCount,
            processedCount + processingFiles.size
        )
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun completeProcessingFile(fileName: String) {
        processingFiles.remove(fileName)
        completedFiles.add(fileName)
        processedCount++
        val notification = createNotification(
            "文件夹监控服务",
            "已处理 $processedCount 个文件，监控中...",
            processedCount,
            processedCount + processingFiles.size
        )
        startForeground(NOTIFICATION_ID, notification)
    }

    // 在服务停止时清理状态
    private fun stopMonitoring() {
        isMonitoring = false
        monitoringJob?.cancel()
        monitoringJob = null

        // 修复：清理状态，表示服务已停止
        val preferencesManager = PreferencesManager(this)
        preferencesManager.isMonitoring = false
        preferencesManager.monitoringSwitchEnabled = false

        val notification = createNotification("监控已停止")
        startForeground(NOTIFICATION_ID, notification)

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
