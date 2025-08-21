package cn.alittlecookie.lut2photo.lut2photo.service

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
        const val EXTRA_INPUT_FOLDER = "input_folder"
        const val EXTRA_OUTPUT_FOLDER = "output_folder"
        const val EXTRA_LUT_FILE_PATH = "lut_file_path"
        const val EXTRA_STRENGTH = "strength"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_DITHER = "dither"
    }


    private var isLutLoaded = false
    private var isMonitoring = false
    private var monitoringJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var processedCount = 0
    private var currentLutName = ""

    // 监控参数
    private var inputFolderUri: String = ""
    private var outputFolderUri: String = ""
    private var lutFilePath: String = ""
    private var processingParams: ILutProcessor.ProcessingParams? = null

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

    // 处理器设置变化广播接收器
    private val processorSettingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "cn.alittlecookie.lut2photo.PROCESSOR_SETTING_CHANGED") {
                val processorType = intent.getStringExtra("processorType")
                Log.d(TAG, "Received processor setting change: $processorType")

                // 更新ThreadManager的处理器设置
                threadManager.updateProcessorFromSettings()  // 移除?操作符
                Log.d(TAG, "ThreadManager processor setting updated")
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        threadManager = ThreadManager(this)

        // 注册广播接收器
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                processorSettingReceiver,
                IntentFilter("PROCESSOR_SETTING_CHANGED"),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            // 在 Android 13 以下的设备上，使用旧方法注册
            // 注意：在旧版本上，默认行为是 RECEIVER_EXPORTED，
            // 如果你需要 NOT_EXPORTED 的行为，此方案不安全，请看方案1的注意点。
            registerReceiver(
                processorSettingReceiver,
                IntentFilter("PROCESSOR_SETTING_CHANGED")
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
                val strength = intent.getIntExtra(EXTRA_STRENGTH, 100)
                val quality = intent.getIntExtra(EXTRA_QUALITY, 90)
                val dither = intent.getStringExtra(EXTRA_DITHER) ?: "none"

                startMonitoring(inputFolder, outputFolder, strength, quality, dither, lutFilePath)
            }
            ACTION_STOP_MONITORING -> {
                stopMonitoring()
            }
            else -> {
                // 修复：如果没有明确的action，不显示启动通知
                Log.w(TAG, "服务启动但没有明确的action,已停止服务")
                stopMonitoring()
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
        lutFilePath: String
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

        // 修复：正确设置LUT文件名
        val lutFile = File(lutFilePath)
        currentLutName = lutFile.nameWithoutExtension

        // 修复：正确的抖动类型转换
        this.processingParams = ILutProcessor.ProcessingParams(
            strength = strength / 100f,
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
                
                // 加载LUT文件
                if (lutFile.exists()) {
                    threadManager.loadLut(lutFile.inputStream())
                    isLutLoaded = true
                    Log.d(TAG, "LUT文件加载成功: $currentLutName")
                } else {
                    Log.e(TAG, "LUT文件不存在: $lutFilePath")
                    return@launch
                }

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
                parts.size >= 2 && parts[1] == fileName
            } catch (_: Exception) {
                false
            }
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
        val recordString =
            "$timestamp|$fileName|$inputPath|$outputPath|处理完成|$lutFileName|${params.strength}|${params.quality}|${params.ditherType.name}"

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

                        // 使用submitTask处理图片
                        val processedBitmap = suspendCoroutine { continuation ->
                            threadManager.submitTask(
                                bitmap = correctedBitmap,
                                params = params,
                                onComplete = { result ->
                                    continuation.resume(result.getOrNull())
                                }
                            )
                        }

                        if (processedBitmap != null) {
                            // 修复：使用正确的文件命名格式
                            val originalName = inputFile.name?.substringBeforeLast(".") ?: "unknown"
                            val outputFileName = "${originalName}-${currentLutName}.jpg"
                            val outputFile = outputDir.createFile("image/jpeg", outputFileName)

                            outputFile?.let { file ->
                                contentResolver.openOutputStream(file.uri)?.use { outputStream ->
                                    processedBitmap.compress(
                                        Bitmap.CompressFormat.JPEG,
                                        params.quality,
                                        outputStream
                                    )
                                }

                                // 保存处理记录到历史
                                saveProcessingRecord(
                                    fileName = inputFile.name ?: "unknown",
                                    inputPath = inputFile.uri.toString(),
                                    outputPath = file.uri.toString(),
                                    lutFileName = currentLutName,
                                    params = params
                                )
                            }

                            Log.d(TAG, "文件处理完成: ${inputFile.name}")
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

        // 添加状态同步到PreferencesManager
        val preferencesManager = cn.alittlecookie.lut2photo.lut2photo.utils.PreferencesManager(this)
        preferencesManager.isMonitoring = false
        // 修复：同时清理UI开关状态，防止状态不一致
        preferencesManager.monitoringSwitchEnabled = false

        val notification = createNotification("监控已停止")
        startForeground(NOTIFICATION_ID, notification)

        Log.d(TAG, "文件夹监控已停止，所有状态已清理")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(processorSettingReceiver)
            Log.d(TAG, "Processor setting receiver unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister processor setting receiver", e)
        }

        // 修复：确保状态完全清理，防止服务残留
        val preferencesManager = cn.alittlecookie.lut2photo.lut2photo.utils.PreferencesManager(this)
        preferencesManager.isMonitoring = false
        preferencesManager.monitoringSwitchEnabled = false

        monitoringJob?.cancel()
        runBlocking {
            threadManager.release()
        }

        Log.d(TAG, "服务正在销毁，所有状态已清理")

        // 释放WakeLock
        wakeLock?.takeIf { it.isHeld }?.release()

        // 移除重启定时器
        restartHandler.removeCallbacks(restartRunnable)

        // 如果正在监控，尝试重启服务
        //if (isMonitoring) {
        //  restartService()
        //}
        
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
