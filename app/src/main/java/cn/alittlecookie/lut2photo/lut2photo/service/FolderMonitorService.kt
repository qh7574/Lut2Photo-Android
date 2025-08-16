package cn.alittlecookie.lut2photo.lut2photo.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import cn.alittlecookie.lut2photo.lut2photo.MainActivity
import cn.alittlecookie.lut2photo.lut2photo.core.LutProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class FolderMonitorService : Service() {

    companion object {
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

    private var lutProcessor: LutProcessor? = null
    private var isLutLoaded = false
    private var isMonitoring = false
    private var monitoringJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var processedFiles = mutableSetOf<String>()
    private var processedCount = 0
    private var currentLutName = "" // 添加当前LUT名称变量

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        lutProcessor = LutProcessor()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 立即启动前台服务，避免超时异常
        val initialNotification = createNotification("正在启动监控服务...")
        startForeground(NOTIFICATION_ID, initialNotification)
        
        when (intent?.action) {
            ACTION_START_MONITORING -> {
                val inputFolder =
                    intent.getStringExtra(EXTRA_INPUT_FOLDER) ?: return START_NOT_STICKY
                val outputFolder =
                    intent.getStringExtra(EXTRA_OUTPUT_FOLDER) ?: return START_NOT_STICKY
                val lutFilePath =
                    intent.getStringExtra(EXTRA_LUT_FILE_PATH) ?: return START_NOT_STICKY
                val strength = intent.getIntExtra(EXTRA_STRENGTH, 100)
                val quality = intent.getIntExtra(EXTRA_QUALITY, 90)
                val dither = intent.getStringExtra(EXTRA_DITHER) ?: "none"

                startMonitoring(inputFolder, outputFolder, strength, quality, dither, lutFilePath)
            }

            ACTION_STOP_MONITORING -> {
                stopMonitoring()
            }
        }

        return START_STICKY
    }

    // 在类的成员变量部分添加：
    private var processingFiles = mutableSetOf<String>() // 正在处理的文件
    private var completedFiles = mutableListOf<String>() // 已完成的文件（按完成顺序）
    
    // 修改createNotification方法：
    private fun createNotification(contentText: String): Notification {
        // 创建点击通知时的Intent
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "home")
        }
    
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    
        // 构建详细的通知内容
        val detailedContent = buildNotificationContent(contentText)
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LUT图像处理")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detailedContent)) // 使用BigTextStyle显示多行
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
    
    // 新增方法：构建通知内容
    private fun buildNotificationContent(statusText: String): String {
        val content = StringBuilder()
        content.append(statusText)
        
        if (processingFiles.isNotEmpty() || completedFiles.isNotEmpty()) {
            content.append("\n\n")
            
            // 显示正在处理的文件
            if (processingFiles.isNotEmpty()) {
                content.append("正在处理：\n")
                processingFiles.forEach { fileName ->
                    content.append("⏳ $fileName\n")
                }
            }
            
            // 显示已完成的文件（最近的几个）
            if (completedFiles.isNotEmpty()) {
                if (processingFiles.isNotEmpty()) content.append("\n")
                content.append("已完成：\n")
                // 只显示最近完成的5个文件，避免通知过长
                val recentCompleted = completedFiles.takeLast(5)
                recentCompleted.forEach { fileName ->
                    content.append("✅ $fileName\n")
                }
                if (completedFiles.size > 5) {
                    content.append("... 及其他 ${completedFiles.size - 5} 个文件\n")
                }
            }
            
            // 显示统计信息
            content.append("\n总计：${completedFiles.size} 已完成，${processingFiles.size} 处理中")
        }
        
        return content.toString()
    }
    
    // 新增方法：开始处理文件时调用
    private fun startProcessingFile(fileName: String) {
        processingFiles.add(fileName)
        updateNotification("正在处理文件...")
    }
    
    // 新增方法：完成处理文件时调用
    private fun completeProcessingFile(fileName: String) {
        processingFiles.remove(fileName)
        completedFiles.add(fileName)
        updateNotification("处理完成")
    }
    
    // 新增方法：更新通知
    private fun updateNotification(statusText: String) {
        val notification = createNotification(statusText)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "文件夹监控服务",
                NotificationManager.IMPORTANCE_HIGH // 改为HIGH重要性
            ).apply {
                description = "LUT图像处理文件夹监控"
                setShowBadge(true)
                enableLights(true)
                enableVibration(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
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
            Log.w("FolderMonitorService", "监控已在运行中")
            return
        }

        isMonitoring = true
        currentLutName = File(lutFilePath).nameWithoutExtension
        
        // 重置计数
        processedCount = 0
        
        // 从SharedPreferences加载已处理文件历史记录
        loadProcessedFilesFromHistory()
        
        Log.d("FolderMonitorService", "开始监控服务")
        Log.d("FolderMonitorService", "输入文件夹: $inputFolder")
        Log.d("FolderMonitorService", "输出文件夹: $outputFolder")
        Log.d("FolderMonitorService", "LUT文件路径: $lutFilePath")
        Log.d("FolderMonitorService", "强度: $strength, 质量: $quality, 抖动: $dither")

        serviceScope.launch {
            // 在startMonitoring方法中，将第174行附近的代码修改为：
            try {
                Log.d("FolderMonitorService", "开始加载LUT文件")
                // 更新通知状态
                val loadingNotification = createNotification("正在加载LUT文件...")
                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager.notify(NOTIFICATION_ID, loadingNotification)

                // 加载LUT文件
                val lutFile = File(lutFilePath)
                if (!lutFile.exists()) {
                    Log.e("FolderMonitorService", "LUT文件不存在: $lutFilePath")
                    val errorNotification = createNotification("LUT文件不存在")
                    notificationManager.notify(NOTIFICATION_ID, errorNotification)
                    stopMonitoring()
                    return@launch
                }

                // 修复：使用正确的方法名和参数类型
                val lutLoaded = lutFile.inputStream().use { inputStream ->
                    lutProcessor?.loadCubeLut(inputStream) ?: false
                }
                
                if (!lutLoaded) {
                    Log.e("FolderMonitorService", "LUT文件加载失败")
                    val errorNotification = createNotification("LUT文件加载失败")
                    notificationManager.notify(NOTIFICATION_ID, errorNotification)
                    stopMonitoring()
                    return@launch
                }
                
                isLutLoaded = true
                Log.d("FolderMonitorService", "LUT文件加载成功")
            } catch (e: Exception) {
                Log.e("FolderMonitorService", "加载LUT文件失败: ${e.message}", e)
                val errorNotification = createNotification("LUT文件加载失败: ${e.message}")
                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager.notify(NOTIFICATION_ID, errorNotification)
                stopMonitoring()
                return@launch
            }

            if (!isLutLoaded) {
                Log.e("FolderMonitorService", "LUT未加载，无法开始监控")
                val errorNotification = createNotification("LUT未加载，无法开始监控")
                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager.notify(NOTIFICATION_ID, errorNotification)
                stopMonitoring()
                return@launch
            }

            Log.d("FolderMonitorService", "LUT加载成功，开始监控文件夹")
            val monitoringNotification = createNotification("正在监控文件夹")
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, monitoringNotification)

            val inputUri = Uri.parse(inputFolder)
            val outputUri = Uri.parse(outputFolder)

            Log.d("FolderMonitorService", "开始处理现有文件")
            processExistingFiles(inputUri, outputUri, strength, quality, dither, lutFilePath)
            
            Log.d("FolderMonitorService", "开始监控新文件")
            // **整合监控逻辑到这里，不再调用startDocumentFileMonitoring**
            startContinuousMonitoring(inputUri, outputUri, strength, quality, dither, lutFilePath)
        }
    }

    private fun isImageAlreadyProcessed(fileName: String): Boolean {
        val prefs = getSharedPreferences("processing_history", MODE_PRIVATE)
        val records = prefs.getStringSet("records", emptySet()) ?: emptySet()

        return records.any { record ->
            val parts = record.split("|")
            if (parts.size >= 2) {
                parts[1] == fileName
            } else {
                false
            }
        }
    }

    private fun processExistingFiles(
        inputUri: Uri,
        outputUri: Uri,
        strength: Int,
        quality: Int,
        dither: String,
        lutFilePath: String
    ) {
        Log.d("FolderMonitorService", "开始处理现有文件")
        try {
            val inputDir = DocumentFile.fromTreeUri(this, inputUri)
            val outputDir = DocumentFile.fromTreeUri(this, outputUri)

            if (inputDir == null) {
                Log.e("FolderMonitorService", "无法访问输入文件夹: $inputUri")
                return
            }

            if (outputDir == null) {
                Log.e("FolderMonitorService", "无法访问输出文件夹: $outputUri")
                return
            }

            val files = inputDir.listFiles()
            Log.d("FolderMonitorService", "输入文件夹中共有${files.size}个文件")

            files.forEach { file ->
                if (file.isFile && isImageFile(file.name ?: "")) {
                    val fileName = file.name ?: ""
                    if (isImageAlreadyProcessed(fileName)) {
                        Log.d("FolderMonitorService", "跳过已处理的图片文件: $fileName")
                    } else {
                        Log.d("FolderMonitorService", "发现新图片文件: $fileName")
                        serviceScope.launch {
                            processDocumentFile(
                                file,
                                outputDir,
                                strength.toFloat(),
                                quality,
                                dither,
                                lutFilePath
                            )
                        }
                    }
                } else {
                    Log.d("FolderMonitorService", "跳过非图片文件: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Log.e("FolderMonitorService", "处理现有文件时发生错误: ${e.message}", e)
            e.printStackTrace()
        }
    }

    private fun startContinuousMonitoring(
        inputUri: Uri,
        outputUri: Uri,
        strength: Int,
        quality: Int,
        dither: String,
        lutFilePath: String
    ) {
        Log.d("FolderMonitorService", "开始持续监控循环")
        
        monitoringJob = serviceScope.launch {
            while (isActive && isMonitoring) {
                try {
                    Log.d("FolderMonitorService", "执行监控检查...")

                    // 权限检查
                    if (!hasUriPermission(inputUri) || !hasUriPermission(outputUri)) {
                        Log.e("FolderMonitorService", "文件夹访问权限已失效，停止监控")
                        val errorNotification = createNotification("文件夹访问权限已失效，请重新选择文件夹")
                        val notificationManager = getSystemService(NotificationManager::class.java)
                        notificationManager.notify(NOTIFICATION_ID, errorNotification)
                        stopMonitoring()
                        return@launch
                    }

                    val inputDir = DocumentFile.fromTreeUri(this@FolderMonitorService, inputUri)
                    val outputDir = DocumentFile.fromTreeUri(this@FolderMonitorService, outputUri)

                    if (inputDir != null && outputDir != null) {
                        val currentFiles = inputDir.listFiles()
                            .filter { it.isFile && isImageFile(it.name ?: "") }
                            .map { it.name ?: "" }
                            .toSet()

                        Log.d("FolderMonitorService", "当前文件数量: ${currentFiles.size}")
                        Log.d("FolderMonitorService", "已处理文件数量: ${processedFiles.size}")

                        // 检查新文件
                        val newFiles = currentFiles.filter { fileName ->
                            !processedFiles.contains(fileName) && !isImageAlreadyProcessed(fileName)
                        }

                        if (newFiles.isNotEmpty()) {
                            Log.d("FolderMonitorService", "发现${newFiles.size}个新文件: $newFiles")

                            newFiles.forEach { fileName ->
                                val file = inputDir.listFiles().find { it.name == fileName }
                                file?.let {
                                    Log.d("FolderMonitorService", "准备处理新文件: $fileName")
                                    // 等待文件写入完成
                                    delay(3000)
                                    processDocumentFile(
                                        it,
                                        outputDir,
                                        strength.toFloat(),
                                        quality,
                                        dither,
                                        lutFilePath
                                    )
                                }
                            }
                        } else {
                            Log.d("FolderMonitorService", "未发现新文件")
                        }
                    } else {
                        Log.e("FolderMonitorService", "无法访问输入或输出文件夹")
                    }
                } catch (e: SecurityException) {
                    Log.e("FolderMonitorService", "权限错误: ${e.message}", e)
                    val errorNotification = createNotification("文件夹访问权限不足，请重新授权")
                    val notificationManager = getSystemService(NotificationManager::class.java)
                    notificationManager.notify(NOTIFICATION_ID, errorNotification)
                    stopMonitoring()
                    return@launch
                } catch (e: Exception) {
                    Log.e("FolderMonitorService", "监控过程中发生错误: ${e.message}", e)
                    e.printStackTrace()
                }

                Log.d("FolderMonitorService", "等待2秒后进行下次检查")
                delay(2000)
            }

            Log.d("FolderMonitorService", "监控循环结束")
        }
    }

    private suspend fun processDocumentFile(
        documentFile: DocumentFile,
        outputDir: DocumentFile,
        strength: Float,
        quality: Int,
        dither: String,
        lutFilePath: String? = null
    ) {
        val fileName = documentFile.name ?: return
        
        // 开始处理文件
        startProcessingFile(fileName)
        
        try {
            // **增强的重复检查逻辑**
            if (processedFiles.contains(fileName)) {
                Log.d("FolderMonitorService", "文件已在当前会话中处理过，跳过: $fileName")
                return
            }
            
            if (isImageAlreadyProcessed(fileName)) {
                Log.d("FolderMonitorService", "文件已在历史记录中处理过，跳过: $fileName")
                // 同时添加到当前会话的processedFiles中
                processedFiles.add(fileName)
                return
            }
            
            // 添加到已处理列表
            processedFiles.add(fileName)
            
            Log.d("FolderMonitorService", "开始解码图片: $fileName")
            val bitmap = contentResolver.openInputStream(documentFile.uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }

            if (bitmap == null) {
                Log.e("FolderMonitorService", "无法解码图片: $fileName")
                return
            }

            Log.d(
                "FolderMonitorService",
                "图片解码成功: $fileName, 尺寸: ${bitmap.width}x${bitmap.height}"
            )

            val ditherType = when (dither) {
                "floyd" -> LutProcessor.DitherType.FLOYD_STEINBERG
                "random" -> LutProcessor.DitherType.RANDOM
                else -> LutProcessor.DitherType.NONE
            }

            Log.d(
                "FolderMonitorService",
                "处理参数 - 强度: $strength, 质量: $quality, 抖动: $dither"
            )
            val params = LutProcessor.ProcessingParams(strength.toInt(), quality, ditherType)

            Log.d("FolderMonitorService", "开始应用LUT处理: $fileName")
            val processedBitmap = lutProcessor?.processImage(bitmap, params)

            if (processedBitmap == null) {
                Log.e("FolderMonitorService", "LUT处理失败: $fileName")
                throw Exception("处理器未初始化或处理失败")
            }

            Log.d("FolderMonitorService", "LUT处理成功: $fileName")

            // 创建输出文件
            val originalName = fileName.substringBeforeLast(".")
            val extension = fileName.substringAfterLast(".", "jpg")
            val outputFileName = "$originalName-$currentLutName.$extension"
            val outputFile = outputDir.createFile("image/jpeg", outputFileName)

            Log.d("FolderMonitorService", "创建输出文件: $outputFileName")
            Log.d("FolderMonitorService", "输出文件URI: ${outputFile?.uri}")

            // 更新媒体库
            outputFile?.uri?.let { uri ->
                try {
                    MediaScannerConnection.scanFile(
                        this@FolderMonitorService,
                        arrayOf(uri.toString()),
                        arrayOf("image/*")
                    ) { path, uri ->
                        Log.d("FolderMonitorService", "媒体库已更新: $path")
                    }
                } catch (e: Exception) {
                    Log.w("FolderMonitorService", "更新媒体库失败: ${e.message}")
                }
            }

            if (outputFile == null) {
                Log.e("FolderMonitorService", "无法创建输出文件: $fileName")
                throw Exception("无法创建输出文件")
            }

            contentResolver.openOutputStream(outputFile.uri)?.use { outputStream ->
                val compressed =
                    processedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
                Log.d("FolderMonitorService", "图片压缩保存结果: $compressed")
                Log.d("FolderMonitorService", "文件已保存到: ${outputFile.uri}")
            }

            // 在文件处理成功后，替换原来的通知更新代码：
            Log.d("FolderMonitorService", "文件处理完成: $fileName")
            
            // 完成处理文件
            completeProcessingFile(fileName)
            
            // 在成功处理后增加计数并发送广播
            processedCount++
            recordProcessing(fileName, documentFile.uri, outputFile.uri, lutFilePath, strength.toInt(), quality, dither)
            Log.d("FolderMonitorService", "处理记录已保存: $fileName")

            // 发送广播通知UI更新
            val intent = Intent("cn.alittlecookie.lut2photo.PROCESSING_UPDATE")
            intent.putExtra("processed_count", processedCount)
            intent.putExtra("file_name", fileName)
            sendBroadcast(intent)

        } catch (e: Exception) {
            // 处理失败时从已处理列表中移除
            processedFiles.remove(fileName)
            Log.e("FolderMonitorService", "处理文件失败: $fileName, 错误: ${e.message}", e)
            e.printStackTrace()
            val errorNotification = createNotification("处理失败: $fileName")
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, errorNotification)
        }
    }

    private fun recordProcessing(
        fileName: String, 
        inputUri: Uri, 
        outputUri: Uri?, 
        lutFilePath: String? = null,
        strength: Int = 60,
        quality: Int = 90,
        ditherType: String = "none"
    ) {
        try {
            val prefs = getSharedPreferences("processing_history", MODE_PRIVATE)
            val timestamp = System.currentTimeMillis()

            // 获取LUT文件名
            val lutFileName = lutFilePath?.let { File(it).name } ?: "未知"

            // 使用完整格式存储，包含所有必要字段
            val strengthFloat = strength / 100f // 转换为0-1的浮点数
            val ditherTypeFormatted = when (ditherType.lowercase()) {
                "floyd" -> "Floyd"
                "random" -> "Random"
                else -> "None"
            }

            val record =
                "$timestamp|$fileName|$inputUri|${outputUri ?: ""}|处理成功|$lutFileName|$strengthFloat|$quality|$ditherTypeFormatted"

            // 修复：创建新的可变集合，避免并发问题
            val existingRecords = prefs.getStringSet("records", emptySet()) ?: emptySet()
            val mutableRecords = existingRecords.toMutableSet()
            mutableRecords.add(record)

            // 按时间戳排序并限制数量，避免无限增长
            val sortedRecords = mutableRecords.sortedByDescending {
                it.split("|")[0].toLongOrNull() ?: 0L
            }.take(100)

            // 使用commit()确保立即写入
            val success = prefs.edit()
                .putStringSet("records", sortedRecords.toSet())
                .commit()

            if (success) {
                Log.d("FolderMonitorService", "处理记录保存成功: $fileName")
            } else {
                Log.e("FolderMonitorService", "处理记录保存失败: $fileName")
            }

        } catch (e: Exception) {
            Log.e("FolderMonitorService", "保存处理记录时发生错误: ${e.message}", e)
        }
    }
    
    private fun hasUriPermission(uri: Uri): Boolean {
        return try {
            // 检查是否有持久化权限
            val persistedUris = contentResolver.persistedUriPermissions
            val hasPermission = persistedUris.any { permission ->
                permission.uri == uri &&
                        permission.isReadPermission &&
                        permission.isWritePermission
            }

            if (hasPermission) {
                Log.d("FolderMonitorService", "URI权限检查通过: $uri")
                return true
            }

            // 如果没有持久化权限，尝试直接访问测试
            val documentFile = DocumentFile.fromTreeUri(this, uri)
            val canAccess =
                documentFile?.exists() == true && documentFile.canRead() && documentFile.canWrite()

            Log.d("FolderMonitorService", "URI访问测试结果: $canAccess for $uri")
            canAccess
        } catch (e: Exception) {
            Log.e("FolderMonitorService", "权限检查失败: ${e.message}")
            false
        }
    }
    
    private fun isImageFile(filename: String): Boolean {
        return filename.lowercase().endsWith(".jpg") ||
                filename.lowercase().endsWith(".jpeg") ||
                filename.lowercase().endsWith(".png") ||
                filename.lowercase().endsWith(".bmp") ||
                filename.lowercase().endsWith(".tiff")
    }
    
    private fun stopMonitoring() {
        isMonitoring = false
        monitoringJob?.cancel()
        monitoringJob = null
        // 重置计数和状态
        processedCount = 0
        processedFiles.clear()
        processingFiles.clear() // 清空正在处理的文件列表
        completedFiles.clear() // 清空已完成的文件列表
        stopForeground(true)
        stopSelf()
    }
    
    // 新增方法：从SharedPreferences加载已处理文件历史
    private fun loadProcessedFilesFromHistory() {
        val prefs = getSharedPreferences("processing_history", MODE_PRIVATE)
        val records = prefs.getStringSet("records", emptySet()) ?: emptySet()
        
        // 清空当前的processedFiles集合
        processedFiles.clear()
        
        // 从历史记录中提取文件名并添加到processedFiles
        records.forEach { record: String ->
            val parts = record.split("|")
            if (parts.size >= 2) {
                val fileName = parts[1]
                processedFiles.add(fileName)
            }
        }
        
        Log.d("FolderMonitorService", "从历史记录加载了 ${processedFiles.size} 个已处理文件")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // 确保在服务销毁前同步所有数据
        try {
            val prefs = getSharedPreferences("processing_history", MODE_PRIVATE)
            prefs.edit().commit() // 强制同步
        } catch (e: Exception) {
            Log.e("FolderMonitorService", "服务销毁时同步数据失败", e)
        }
        stopMonitoring()
    }
}
