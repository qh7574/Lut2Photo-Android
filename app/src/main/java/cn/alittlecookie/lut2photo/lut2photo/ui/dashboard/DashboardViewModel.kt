package cn.alittlecookie.lut2photo.lut2photo.ui.dashboard

import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import cn.alittlecookie.lut2photo.lut2photo.R
import cn.alittlecookie.lut2photo.lut2photo.core.ILutProcessor
import cn.alittlecookie.lut2photo.lut2photo.core.ThreadManager
import cn.alittlecookie.lut2photo.lut2photo.model.ImageItem
import cn.alittlecookie.lut2photo.lut2photo.model.LutItem
import cn.alittlecookie.lut2photo.lut2photo.model.ProcessingRecord
import cn.alittlecookie.lut2photo.lut2photo.utils.LutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.suspendCoroutine

data class ProcessingResult(
    val isSuccess: Boolean,
    val processedCount: Int,
    val totalCount: Int,
    val message: String
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "DashboardViewModel"
    }

    private val _processingCompleted = MutableLiveData<ProcessingResult?>()
    val processingCompleted: LiveData<ProcessingResult?> = _processingCompleted

    private val _isProcessing = MutableLiveData<Boolean>().apply {
        value = false
    }

    private val _processedCount = MutableLiveData<Int>().apply {
        value = 0
    }
    val processedCount: LiveData<Int> = _processedCount

    private val _totalCount = MutableLiveData<Int>().apply {
        value = 0
    }
    val totalCount: LiveData<Int> = _totalCount

    private val _statusMessage = MutableLiveData<String>().apply {
        value = ""
    }

    private val _selectedImages = MutableLiveData<List<ImageItem>>().apply {
        value = emptyList()
    }
    val selectedImages: LiveData<List<ImageItem>> = _selectedImages


    private val _processingHistory = MutableLiveData<List<ProcessingRecord>>().apply {
        value = emptyList()
    }

    private val _processingStatus = MutableLiveData<String>().apply {
        value = getApplication<Application>().getString(R.string.status_ready)
    }
    val processingStatus: LiveData<String> = _processingStatus

    private val threadManager = ThreadManager(application)
    private val lutManager = LutManager(application)
    private var processingJob: Job? = null
    private val processedCounter = AtomicInteger(0)

    // 处理器设置变化广播接收器
    private val processorSettingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "cn.alittlecookie.lut2photo.PROCESSOR_SETTING_CHANGED") {
                val processorType = intent.getStringExtra("processorType")
                Log.d("DashboardViewModel", "Received processor setting change: $processorType")

                // 更新ThreadManager的处理器设置
                threadManager.updateProcessorFromSettings()
                Log.d("DashboardViewModel", "ThreadManager processor setting updated")
            }
        }
    }

    init {
        // 注册广播接收器
        try {
            val filter = IntentFilter("cn.alittlecookie.lut2photo.PROCESSOR_SETTING_CHANGED")
            ContextCompat.registerReceiver(
                application,
                processorSettingReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            Log.d("DashboardViewModel", "Processor setting receiver registered")
        } catch (e: Exception) {
            Log.e("DashboardViewModel", "Failed to register processor setting receiver", e)
        }
    }

    // 在addImages方法中，修改ImageItem的创建方式
    fun addImages(uris: List<Uri>) {
        viewModelScope.launch {
            val newImages = uris.mapNotNull { uri ->
                try {
                    // 生成预览bitmap
                    val previewBitmap = generatePreviewBitmap(uri)
                    if (previewBitmap != null) {
                        ImageItem(uri, previewBitmap)
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create ImageItem for $uri", e)
                    null
                }
            }
            // 修改这里：更新 _selectedImages 而不是 _images
            _selectedImages.value = _selectedImages.value + newImages
        }
    }

    // 添加生成预览bitmap的方法
    private suspend fun generatePreviewBitmap(uri: Uri): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                val inputStream = getApplication<Application>().contentResolver.openInputStream(uri)
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(inputStream, null, options)
                inputStream?.close()

                // 计算缩放比例
                val sampleSize = calculateInSampleSize(options, 200, 200)

                val finalInputStream =
                    getApplication<Application>().contentResolver.openInputStream(uri)
                val finalOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                }
                val bitmap = BitmapFactory.decodeStream(finalInputStream, null, finalOptions)
                finalInputStream?.close()
                bitmap
            } catch (e: Exception) {
                Log.e(TAG, "Failed to generate preview bitmap", e)
                null
            }
        }
    }

    // 添加计算缩放比例的方法
    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    fun removeImage(imageItem: ImageItem) {
        val currentImages = _selectedImages.value?.toMutableList() ?: return
        currentImages.remove(imageItem)
        _selectedImages.value = currentImages
    }

    fun clearImages() {
        _selectedImages.value = emptyList()
    }

    fun resetProcessingCompleted() {
        _processingCompleted.value = null
    }

    fun addProcessingRecord(
        timestamp: Long = System.currentTimeMillis(),
        fileName: String,
        inputPath: String,
        outputPath: String,
        status: String,
        lutFileName: String = "",
        strength: Float = 0f,
        quality: Float = 0f,  // 保持Float类型
        ditherType: String = ""
    ) {
        val record = ProcessingRecord(
            timestamp = timestamp,
            fileName = fileName,
            inputPath = inputPath,
            outputPath = outputPath,
            status = status,
            lutFileName = lutFileName,
            strength = strength,
            quality = quality.toInt(), // 转换为Int类型
            ditherType = ditherType
        )
        saveProcessingRecord(record)
    }

    fun saveProcessingRecord(record: ProcessingRecord) {
        viewModelScope.launch {
            // 保存到内存
            val currentHistory = _processingHistory.value?.toMutableList() ?: mutableListOf()
            currentHistory.add(0, record)
            if (currentHistory.size > 100) {
                currentHistory.removeAt(currentHistory.size - 1)
            }
            _processingHistory.value = currentHistory

            // 同时保存到SharedPreferences
            withContext(Dispatchers.IO) {
                val prefs = getApplication<Application>().getSharedPreferences(
                    "processing_history",
                    Context.MODE_PRIVATE
                )
                val existingRecords =
                    prefs.getStringSet("records", emptySet())?.toMutableSet() ?: mutableSetOf()

                // 创建记录字符串（格式：timestamp|fileName|inputPath|outputPath|status|lutFileName|strength|quality|ditherType）
                val recordString =
                    "${record.timestamp}|${record.fileName}|${record.inputPath}|${record.outputPath}|${record.status}|${record.lutFileName}|${record.strength}|${record.quality}|${record.ditherType}"
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
                    existingRecords.addAll(sortedRecords.take(100).map { it.second })
                }

                prefs.edit { putStringSet("records", existingRecords) }

                // 发送广播通知历史页面更新
                val intent = Intent("cn.alittlecookie.lut2photo.PROCESSING_UPDATE")
                getApplication<Application>().sendBroadcast(intent)
            }
        }
    }

    private suspend fun loadImageBitmap(imageItem: ImageItem): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                getApplication<Application>().contentResolver.openInputStream(imageItem.uri)
                    ?.use { inputStream ->
                        // 首先读取EXIF信息
                        val exif = ExifInterface(inputStream)
                        val orientation = exif.getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL
                        )

                        // 重新打开流来解码图像
                        getApplication<Application>().contentResolver.openInputStream(imageItem.uri)
                            ?.use { decodeStream ->
                                val bitmap = BitmapFactory.decodeStream(decodeStream)
                                bitmap?.let { applyExifOrientation(it, orientation) }
                            }
                    }
            } catch (e: Exception) {
                Log.e(
                    "DashboardViewModel",
                    "加载图片失败: ${getFileNameFromUri(imageItem.uri)}",
                    e
                )
                null
            }
        }
    }

    // 添加EXIF方向处理方法
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
            Log.e("DashboardViewModel", "内存不足，无法旋转图像", e)
            bitmap
        }
    }

    private fun finishProcessing(processedCount: Int, totalCount: Int) {
        val message = if (processedCount == totalCount) {
            getApplication<Application>().getString(
                R.string.processing_completed_success,
                processedCount
            )
        } else {
            getApplication<Application>().getString(
                R.string.processing_completed_partial,
                processedCount,
                totalCount
            )
        }

        _statusMessage.postValue(message)
        _processingCompleted.postValue(
            ProcessingResult(
                isSuccess = processedCount > 0,
                processedCount = processedCount,
                totalCount = totalCount,
                message = message
            )
        )
    }

    @SuppressLint("StringFormatInvalid", "StringFormatMatches")
    fun startProcessing(
        images: List<ImageItem>,
        lutItem: LutItem,
        params: ILutProcessor.ProcessingParams,
        outputFolderUri: String? = null
    ) {
        if (processingJob?.isActive == true) {
            Log.w("DashboardViewModel", "处理任务已在运行中")
            return
        }
    
        processingJob = viewModelScope.launch {
            try {
                // 修复：在开始处理前同步处理器设置
                threadManager.updateProcessorFromSettings()
                Log.d("DashboardViewModel", "处理器设置已同步")

                _isProcessing.postValue(true)
                _totalCount.postValue(images.size)
                processedCounter.set(0)
                _processedCount.postValue(0)
                _statusMessage.postValue(getApplication<Application>().getString(R.string.status_processing))

                // 修复：使用LutManager获取完整路径
                val lutFilePath = lutManager.getLutFilePath(lutItem)
                val lutFile = File(lutFilePath)
                if (!lutFile.exists()) {
                    throw Exception("LUT文件不存在: ${lutItem.name}")
                }

                threadManager.loadLut(lutFile.inputStream())
                Log.d("DashboardViewModel", "LUT加载成功: ${lutItem.name}")

                // 处理每张图片
                for ((index, imageItem) in images.withIndex()) {
                    if (!isActive) break

                    try {
                        _statusMessage.postValue(
                            getApplication<Application>().getString(
                                R.string.processing_image_progress,
                                index + 1,
                                images.size,
                                getFileNameFromUri(imageItem.uri)
                            )
                        )

                        // 修复后的异步处理逻辑
                        val bitmap = loadImageBitmap(imageItem)
                        // 在 startProcessing 方法中修复硬编码日志
                        if (bitmap != null) {
                            // 获取实际将要使用的处理器类型
                            val processorInfo = threadManager.getProcessorInfo()
                            val actualProcessor = processorInfo.preferredProcessor.name

                            Log.d(
                                "DashboardViewModel",
                                "开始提交${actualProcessor}处理任务，图片尺寸: ${bitmap.width}x${bitmap.height}"
                            )

                            // 使用suspendCoroutine正确处理异步回调
                            val processedBitmap = suspendCoroutine<Bitmap?> { continuation ->
                                threadManager.submitTask(
                                    bitmap = bitmap,
                                    params = params,
                                    onProgress = { progress ->
                                        Log.d("DashboardViewModel", "处理进度: $progress")
                                    },
                                    onComplete = { result ->
                                        result.fold(
                                            onSuccess = { processedBitmap ->
                                                Log.d("DashboardViewModel", "处理成功，返回结果")
                                                continuation.resumeWith(
                                                    Result.success(
                                                        processedBitmap
                                                    )
                                                )
                                            },
                                            onFailure = { error ->
                                                Log.e("DashboardViewModel", "处理失败", error)
                                                continuation.resumeWith(Result.success(null))
                                            }
                                        )
                                    }
                                )
                            }

                            Log.d(
                                "DashboardViewModel",
                                "处理完成，processedBitmap: ${if (processedBitmap != null) "${processedBitmap.width}x${processedBitmap.height}" else "null"}"
                            )

                            if (processedBitmap != null) {
                                Log.d("DashboardViewModel", "开始保存处理后的图片")
                                // 修改saveProcessedImage方法调用
                                val outputPath = saveProcessedImage(
                                    processedBitmap,
                                    imageItem,
                                    outputFolderUri,
                                    params,
                                    lutItem.name
                                )
                                Log.d("DashboardViewModel", "图片保存完成，路径: $outputPath")

                                // 添加处理记录
                                Log.d("DashboardViewModel", "添加处理记录到历史")
                                addProcessingRecord(
                                    fileName = getFileNameFromUri(imageItem.uri),
                                    inputPath = imageItem.uri.toString(),
                                    outputPath = outputPath,
                                    status = "成功",
                                    lutFileName = lutItem.name,
                                    strength = params.strength,
                                    quality = params.quality.toFloat(),
                                    ditherType = params.ditherType.name
                                )

                                val currentCount = processedCounter.incrementAndGet()
                                _processedCount.postValue(currentCount)
                                Log.d("DashboardViewModel", "处理计数更新: $currentCount")
                            } else {
                                Log.e(
                                    "DashboardViewModel",
                                    "processedBitmap为null，GPU处理可能失败"
                                )
                                handleProcessingError(
                                    imageItem,
                                    lutItem,
                                    params,
                                    Exception("图片处理失败")
                                )
                            }
                        } else {
                            handleProcessingError(
                                imageItem,
                                lutItem,
                                params,
                                Exception("无法加载图片")
                            )
                        }
                    } catch (e: Exception) {
                        handleProcessingError(imageItem, lutItem, params, e)
                    }
                }

                finishProcessing(processedCounter.get(), images.size)

            } catch (e: Exception) {
                Log.e("DashboardViewModel", "处理过程中发生错误", e)
                _statusMessage.postValue("处理失败: ${e.message}")
                _processingCompleted.postValue(
                    ProcessingResult(
                        isSuccess = false,
                        processedCount = processedCounter.get(),
                        totalCount = images.size,
                        message = "处理失败: ${e.message}"
                    )
                )
            } finally {
                _isProcessing.postValue(false)
            }
        }
    }

    private suspend fun saveProcessedImage(
        bitmap: Bitmap,
        imageItem: ImageItem,
        outputFolderUri: String?,
        params: ILutProcessor.ProcessingParams,
        lutName: String
    ): String {
        return withContext(Dispatchers.IO) {
            try {
                // 获取原始文件名（不含扩展名）
                val originalFileName = getFileNameFromUri(imageItem.uri)
                val baseFileName = if (originalFileName.contains(".")) {
                    originalFileName.substringBeforeLast(".")
                } else {
                    originalFileName
                }

                // 获取LUT文件名（不含扩展名）
                val lutFileName = if (lutName.contains(".")) {
                    lutName.substringBeforeLast(".")
                } else {
                    lutName
                }

                // 按照要求的格式命名：<原始文件名>-<lut文件名>.jpg
                val fileName = "${baseFileName}-${lutFileName}.jpg"
                Log.d("DashboardViewModel", "文件名: $fileName")

                val outputPath: String
                val context = getApplication<Application>()

                if (!outputFolderUri.isNullOrEmpty() && outputFolderUri.startsWith("content://")) {
                    // 使用SAF处理content URI
                    val treeUri = outputFolderUri.toUri()
                    val documentFile = DocumentFile.fromTreeUri(context, treeUri)

                    if (documentFile != null && documentFile.exists()) {
                        // 创建新文件
                        val newFile = documentFile.createFile("image/jpeg", fileName)
                        if (newFile != null) {
                            outputPath = newFile.uri.toString()

                            // 保存图片
                            context.contentResolver.openOutputStream(newFile.uri)
                                ?.use { outputStream ->
                                    bitmap.compress(
                                        Bitmap.CompressFormat.JPEG,
                                        params.quality,
                                        outputStream
                                    )
                                }

                            // 尝试保留EXIF信息
                            try {
                                copyExifData(imageItem.uri, newFile.uri)
                            } catch (e: Exception) {
                                Log.w("DashboardViewModel", "无法复制EXIF信息: ${e.message}")
                            }

                            Log.d("DashboardViewModel", "图片保存成功: $outputPath")
                        } else {
                            throw IOException("无法创建文件: $fileName")
                        }
                    } else {
                        throw IOException("输出目录不存在或无法访问")
                    }
                } else {
                    // 使用传统文件路径
                    val outputDir = File(
                        outputFolderUri
                            ?: context.getExternalFilesDir("processed")?.absolutePath ?: ""
                    )
                    if (!outputDir.exists()) {
                        outputDir.mkdirs()
                    }

                    val outputFile = File(outputDir, fileName)
                    outputPath = outputFile.absolutePath

                    // 保存图片
                    FileOutputStream(outputFile).use { outputStream ->
                        bitmap.compress(
                            Bitmap.CompressFormat.JPEG,
                            params.quality,
                            outputStream
                        )
                    }

                    // 尝试保留EXIF信息
                    try {
                        copyExifDataToFile(imageItem.uri, outputFile)
                    } catch (e: Exception) {
                        Log.w("DashboardViewModel", "无法复制EXIF信息: ${e.message}")
                    }

                    Log.d("DashboardViewModel", "图片保存成功: $outputPath")
                }

                outputPath
            } catch (e: Exception) {
                Log.e("DashboardViewModel", "保存图片失败", e)
                throw e
            }
        }
    }

    private fun handleProcessingError(
        imageItem: ImageItem,
        lutItem: LutItem,
        params: ILutProcessor.ProcessingParams,
        error: Throwable
    ) {
        Log.e("DashboardViewModel", "处理图片失败: ${getFileNameFromUri(imageItem.uri)}", error)
        addProcessingRecord(
            fileName = getFileNameFromUri(imageItem.uri),
            inputPath = imageItem.uri.toString(),
            outputPath = "",
            status = "失败: ${error.message}",
            lutFileName = lutItem.name,
            strength = params.strength,
            quality = params.quality.toFloat(), // 修复：转换为Float
            ditherType = params.ditherType.name
        )
    }

    fun stopProcessing() {
        processingJob?.cancel()
        processingJob = null
        _isProcessing.value = false
        _statusMessage.value = getApplication<Application>().getString(R.string.status_ready)
    }

    override fun onCleared() {
        super.onCleared()
        try {
            getApplication<Application>().unregisterReceiver(processorSettingReceiver)
            Log.d("DashboardViewModel", "Processor setting receiver unregistered")
        } catch (e: Exception) {
            Log.e("DashboardViewModel", "Failed to unregister processor setting receiver", e)
        }

        processingJob?.cancel()
        viewModelScope.launch {
            threadManager.release()
        }
    }

    private fun getFileNameFromUri(uri: Uri): String {
        return uri.lastPathSegment ?: "unknown"
    }

    private fun copyExifData(sourceUri: Uri, targetUri: Uri) {
        try {
            val context = getApplication<Application>()

            // 读取源文件的EXIF信息
            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                val sourceExif = ExifInterface(inputStream)

                // 对于DocumentFile，我们需要先将文件下载到临时位置，修改EXIF后再上传
                val tempFile = File.createTempFile("exif_temp", ".jpg", context.cacheDir)

                try {
                    // 复制目标文件到临时位置
                    context.contentResolver.openInputStream(targetUri)
                        ?.use { targetInputStream ->
                            tempFile.outputStream().use { tempOutputStream ->
                                targetInputStream.copyTo(tempOutputStream)
                            }
                        }

                    // 修改临时文件的EXIF
                    val tempExif = ExifInterface(tempFile.absolutePath)
                    copyExifAttributes(sourceExif, tempExif)
                    tempExif.saveAttributes()

                    // 将修改后的文件写回目标URI
                    context.contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                        tempFile.inputStream().use { tempInputStream ->
                            tempInputStream.copyTo(outputStream)
                        }
                    }
                } finally {
                    tempFile.delete()
                }
            }
        } catch (e: Exception) {
            Log.w("DashboardViewModel", "复制EXIF信息失败: ${e.message}")
        }
    }

    private fun copyExifAttributes(source: ExifInterface, target: ExifInterface) {
        val tags = arrayOf(
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_CAMERA_OWNER_NAME,
            ExifInterface.TAG_ARTIST,
            ExifInterface.TAG_COPYRIGHT,
            ExifInterface.TAG_IMAGE_DESCRIPTION,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_X_RESOLUTION,
            ExifInterface.TAG_Y_RESOLUTION,
            ExifInterface.TAG_RESOLUTION_UNIT,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_ISO_SPEED_RATINGS,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_FLASH
        )

        for (tag in tags) {
            val value = source.getAttribute(tag)
            if (value != null) {
                target.setAttribute(tag, value)
            }
        }
    }

    /**
     * 复制EXIF信息从源URI到目标文件（用于传统文件路径）
     */
    private fun copyExifDataToFile(sourceUri: Uri, targetFile: File) {
        try {
            val context = getApplication<Application>()

            // 读取源文件的EXIF信息
            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                val sourceExif = ExifInterface(inputStream)

                // 创建目标文件的EXIF接口
                val targetExif = ExifInterface(targetFile.absolutePath)

                // 复制所有EXIF标签
                val tags = arrayOf(
                    ExifInterface.TAG_DATETIME,
                    ExifInterface.TAG_DATETIME_DIGITIZED,
                    ExifInterface.TAG_DATETIME_ORIGINAL,
                    ExifInterface.TAG_CAMERA_OWNER_NAME,
                    ExifInterface.TAG_ARTIST,
                    ExifInterface.TAG_COPYRIGHT,
                    ExifInterface.TAG_IMAGE_DESCRIPTION,
                    ExifInterface.TAG_MAKE,
                    ExifInterface.TAG_MODEL,
                    ExifInterface.TAG_SOFTWARE,
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.TAG_X_RESOLUTION,
                    ExifInterface.TAG_Y_RESOLUTION,
                    ExifInterface.TAG_RESOLUTION_UNIT,
                    ExifInterface.TAG_GPS_LATITUDE,
                    ExifInterface.TAG_GPS_LONGITUDE,
                    ExifInterface.TAG_GPS_LATITUDE_REF,
                    ExifInterface.TAG_GPS_LONGITUDE_REF,
                    ExifInterface.TAG_GPS_ALTITUDE,
                    ExifInterface.TAG_GPS_ALTITUDE_REF,
                    ExifInterface.TAG_GPS_TIMESTAMP,
                    ExifInterface.TAG_GPS_DATESTAMP,
                    ExifInterface.TAG_EXPOSURE_TIME,
                    ExifInterface.TAG_F_NUMBER,
                    ExifInterface.TAG_ISO_SPEED_RATINGS,
                    ExifInterface.TAG_FOCAL_LENGTH,
                    ExifInterface.TAG_WHITE_BALANCE,
                    ExifInterface.TAG_FLASH
                )

                for (tag in tags) {
                    val value = sourceExif.getAttribute(tag)
                    if (value != null) {
                        targetExif.setAttribute(tag, value)
                    }
                }

                targetExif.saveAttributes()
            }
        } catch (e: Exception) {
            Log.w("DashboardViewModel", "复制EXIF信息失败: ${e.message}")
        }
    }
}