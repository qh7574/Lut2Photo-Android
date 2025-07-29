package cn.alittlecookie.lut2photo.lut2photo.ui.dashboard

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import cn.alittlecookie.lut2photo.lut2photo.core.LutProcessor
import cn.alittlecookie.lut2photo.lut2photo.model.ImageItem
import cn.alittlecookie.lut2photo.lut2photo.model.LutItem
import cn.alittlecookie.lut2photo.lut2photo.model.ProcessingRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import androidx.documentfile.provider.DocumentFile
import cn.alittlecookie.lut2photo.lut2photo.utils.LutManager

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val _isProcessing = MutableLiveData<Boolean>().apply {
        value = false
    }
    val isProcessing: LiveData<Boolean> = _isProcessing

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
    val statusMessage: LiveData<String> = _statusMessage
    
    // 新增：选中的图片列表
    private val _selectedImages = MutableLiveData<List<ImageItem>>().apply {
        value = emptyList()
    }
    val selectedImages: LiveData<List<ImageItem>> = _selectedImages
    
    // 新增：处理历史记录
    private val _processingHistory = MutableLiveData<List<ProcessingRecord>>().apply {
        value = emptyList()
    }
    val processingHistory: LiveData<List<ProcessingRecord>> = _processingHistory
    
    // 新增：处理状态
    private val _processingStatus = MutableLiveData<String>().apply {
        value = "准备就绪"
    }
    val processingStatus: LiveData<String> = _processingStatus

    private val lutProcessor = LutProcessor()
    private var processingJob: kotlinx.coroutines.Job? = null

    fun setProcessing(processing: Boolean) {
        _isProcessing.value = processing
    }

    fun updateProcessedCount(count: Int) {
        _processedCount.value = count
    }

    fun resetProcessedCount() {
        _processedCount.value = 0
    }

    fun setTotalCount(count: Int) {
        _totalCount.value = count
    }

    fun updateStatus(message: String) {
        _statusMessage.value = message
        _processingStatus.value = message
    }
    
    // 新增：添加图片
    fun addImage(uri: Uri) {
        viewModelScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { inputStream ->
                        BitmapFactory.decodeStream(inputStream)
                    }
                }
                
                bitmap?.let {
                    val imageItem = ImageItem(
                        uri = uri,
                        previewBitmap = createPreviewBitmap(it)
                    )
                    
                    val currentList = _selectedImages.value?.toMutableList() ?: mutableListOf()
                    currentList.add(imageItem)
                    _selectedImages.value = currentList
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    // 新增：移除图片
    fun removeImage(imageItem: ImageItem) {
        val currentList = _selectedImages.value?.toMutableList() ?: mutableListOf()
        currentList.remove(imageItem)
        _selectedImages.value = currentList
    }
    
    // 新增：清空图片
    fun clearImages() {
        _selectedImages.value = emptyList()
    }
    
    // 新增：清空历史记录
    fun clearHistory() {
        _processingHistory.value = emptyList()
        // 同时清空SharedPreferences中的记录
        val prefs = getApplication<Application>().getSharedPreferences("processing_history", android.content.Context.MODE_PRIVATE)
        prefs.edit().remove("records").apply()
    }
    
    // 新增：创建预览图片
    private fun createPreviewBitmap(original: Bitmap): Bitmap {
        val maxSize = 200
        val ratio = minOf(maxSize.toFloat() / original.width, maxSize.toFloat() / original.height)
        val width = (original.width * ratio).toInt()
        val height = (original.height * ratio).toInt()
        return Bitmap.createScaledBitmap(original, width, height, true)
    }
    
    // 添加处理完成回调
    private val _processingCompleted = MutableLiveData<ProcessingResult>()
    val processingCompleted: LiveData<ProcessingResult> = _processingCompleted
    
    data class ProcessingResult(
        val isSuccess: Boolean,
        val processedCount: Int,
        val totalCount: Int,
        val message: String
    )
    
    fun startProcessing(
        images: List<ImageItem>,
        lutItem: LutItem,
        params: LutProcessor.ProcessingParams,
        outputFolderUri: String? = null
    ) {
        if (_isProcessing.value == true) return
        
        processingJob = viewModelScope.launch {
            try {
                setProcessing(true)
                setTotalCount(images.size)
                resetProcessedCount()
                updateStatus("正在加载LUT文件...")
                
                // 修复LUT文件路径处理
                val lutLoaded = withContext(Dispatchers.IO) {
                    try {
                        // 修复：使用LutManager的getLutFilePath方法获取完整路径
                        val lutManager = LutManager(getApplication<Application>())
                        val lutFilePath = lutManager.getLutFilePath(lutItem)
                        val lutFile = File(lutFilePath)
                        android.util.Log.d("DashboardViewModel", "尝试加载LUT文件: ${lutFile.absolutePath}")
                        android.util.Log.d("DashboardViewModel", "LUT文件存在: ${lutFile.exists()}")
                        
                        if (!lutFile.exists()) {
                            android.util.Log.e("DashboardViewModel", "LUT文件不存在: ${lutFile.absolutePath}")
                            return@withContext false
                        }
                        
                        if (!lutFile.canRead()) {
                            android.util.Log.e("DashboardViewModel", "LUT文件无法读取: ${lutFile.absolutePath}")
                            return@withContext false
                        }
                        
                        android.util.Log.d("DashboardViewModel", "开始加载LUT文件内容")
                        val result = lutFile.inputStream().use { inputStream ->
                            lutProcessor.loadCubeLut(inputStream)
                        }
                        android.util.Log.d("DashboardViewModel", "LUT文件加载结果: $result")
                        result
                    } catch (e: Exception) {
                        android.util.Log.e("DashboardViewModel", "LUT文件加载异常", e)
                        e.printStackTrace()
                        false
                    }
                }
                
                if (!lutLoaded) {
                    val errorMsg = "LUT文件加载失败，请检查文件路径: ${lutItem.filePath}"
                    android.util.Log.e("DashboardViewModel", errorMsg)
                    updateStatus(errorMsg)
                    setProcessing(false)
                    return@launch
                }
                
                updateStatus("LUT文件加载成功，开始处理图片...")
                android.util.Log.d("DashboardViewModel", "开始处理 ${images.size} 张图片")
                var processedCount = 0
                
                for ((index, imageItem) in images.withIndex()) {
                    if (!_isProcessing.value!!) {
                        android.util.Log.d("DashboardViewModel", "处理被取消")
                        break
                    }
                    
                    try {
                        android.util.Log.d("DashboardViewModel", "处理第${index + 1}张图片: ${imageItem.uri}")
                        updateStatus("处理第 ${index + 1} 张图片，共 ${images.size} 张")
                        updateProcessedCount(index)
                        
                        val bitmap = withContext(Dispatchers.IO) {
                            getApplication<Application>().contentResolver.openInputStream(imageItem.uri)?.use { inputStream ->
                                BitmapFactory.decodeStream(inputStream)
                            }
                        }
                        
                        if (bitmap == null) {
                            android.util.Log.e("DashboardViewModel", "无法解码图片: ${imageItem.uri}")
                            addProcessingRecord(imageItem.uri.toString(), "解码失败", "")
                            continue
                        }
                        
                        android.util.Log.d("DashboardViewModel", "图片解码成功，尺寸: ${bitmap.width}x${bitmap.height}")
                        
                        val processedBitmap = withContext(Dispatchers.Default) {
                            lutProcessor.processImage(bitmap, params)
                        }
                        
                        // 在处理成功时
                        if (processedBitmap != null) {
                            android.util.Log.d("DashboardViewModel", "图片处理成功")
                            val outputPath = saveProcessedImage(processedBitmap, imageItem.uri, params.quality, outputFolderUri, lutItem.name)
                            addProcessingRecord(imageItem.uri.toString(), "处理成功", outputPath, lutItem.name, params)
                            processedCount++
                            android.util.Log.d("DashboardViewModel", "图片保存完成: $outputPath")
                        } else {
                            android.util.Log.e("DashboardViewModel", "图片处理失败")
                            addProcessingRecord(imageItem.uri.toString(), "处理失败", "", lutItem.name, params)
                        }
                        
                    } catch (e: Exception) {
                        android.util.Log.e("DashboardViewModel", "处理图片时发生错误: ${e.message}", e)
                        val errorMsg = "处理第 ${index + 1} 张图片时出错: ${e.message}"
                        updateStatus(errorMsg)
                        addProcessingRecord(imageItem.uri.toString(), "处理失败: ${e.message}", "")
                    }
                }
                
                val finalMsg = if (_isProcessing.value!!) {
                    "处理完成！共处理 $processedCount 张图片"
                } else {
                    "处理已取消"
                }
                android.util.Log.d("DashboardViewModel", finalMsg)
                updateStatus(finalMsg)
                
                // 发送处理完成事件
                _processingCompleted.postValue(
                    ProcessingResult(
                        isSuccess = _isProcessing.value!! && processedCount > 0,
                        processedCount = processedCount,
                        totalCount = images.size,
                        message = finalMsg
                    )
                )
                
            } catch (e: Exception) {
                val errorMsg = "处理过程中发生错误: ${e.message}"
                android.util.Log.e("DashboardViewModel", errorMsg, e)
                e.printStackTrace()
                updateStatus(errorMsg)
                
                // 发送处理失败事件
                _processingCompleted.postValue(
                    ProcessingResult(
                        isSuccess = false,
                        processedCount = 0,
                        totalCount = images.size,
                        message = errorMsg
                    )
                )
            } finally {
                android.util.Log.d("DashboardViewModel", "处理结束，设置isProcessing为false")
                setProcessing(false)
            }
        }
    }
    
    // 重置处理完成状态
    fun resetProcessingCompleted() {
        _processingCompleted.value = null
    }
    
    // 统一的 saveProcessedImage 方法
    private suspend fun saveProcessedImage(
        bitmap: Bitmap, 
        originalUri: Uri, 
        quality: Int,
        outputFolderUri: String? = null,
        lutFileName: String = "unknown"
    ): String = withContext(Dispatchers.IO) {
        try {
            // 3. 生成新的输出文件名格式：<原文件名-lut文件名.jpg>
            val originalFileName = getFileNameFromUri(originalUri)
            val originalNameWithoutExt = originalFileName.substringBeforeLast(".")
            val outputFileName = "${originalNameWithoutExt}-${lutFileName}.jpg"
            
            // 读取原始图片的EXIF信息
            val originalExif = try {
                getApplication<Application>().contentResolver.openInputStream(originalUri)?.use { inputStream ->
                    androidx.exifinterface.media.ExifInterface(inputStream)
                }
            } catch (e: Exception) {
                Log.w("DashboardViewModel", "无法读取EXIF信息: ${e.message}")
                null
            }
            
            val outputFile = if (outputFolderUri != null) {
                // 使用用户指定的输出文件夹
                val outputDir = DocumentFile.fromTreeUri(getApplication(), Uri.parse(outputFolderUri))
                
                // 检查权限
                if (outputDir == null || !hasUriPermission(Uri.parse(outputFolderUri))) {
                    Log.e("DashboardViewModel", "输出文件夹权限不足或无效")
                    return@withContext ""
                }
                
                val outputDocFile = outputDir.createFile("image/jpeg", outputFileName)
                outputDocFile?.let { docFile ->
                    getApplication<Application>().contentResolver.openOutputStream(docFile.uri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    }
                    
                    // 保存EXIF信息到新文件
                    originalExif?.let { exif ->
                        try {
                            getApplication<Application>().contentResolver.openFileDescriptor(docFile.uri, "rw")?.use { pfd ->
                                val newExif = androidx.exifinterface.media.ExifInterface(pfd.fileDescriptor)
                                copyExifData(exif, newExif)
                                newExif.saveAttributes()
                            }
                        } catch (e: Exception) {
                            Log.w("DashboardViewModel", "保存EXIF信息失败: ${e.message}")
                        }
                    }
                    
                    // 通知系统媒体库
                    android.media.MediaScannerConnection.scanFile(
                        getApplication(),
                        arrayOf(docFile.uri.toString()),
                        arrayOf("image/jpeg"),
                        null
                    )
                    
                    docFile.uri.toString()
                } ?: ""
            } else {
                // 默认保存到应用目录
                val file = File(getApplication<Application>().getExternalFilesDir("processed"), outputFileName)
                file.parentFile?.mkdirs()
                
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                }
                
                // 保存EXIF信息
                originalExif?.let { exif ->
                    try {
                        val newExif = androidx.exifinterface.media.ExifInterface(file.absolutePath)
                        copyExifData(exif, newExif)
                        newExif.saveAttributes()
                    } catch (e: Exception) {
                        Log.w("DashboardViewModel", "保存EXIF信息失败: ${e.message}")
                    }
                }
                
                // 添加到媒体库
                MediaStore.Images.Media.insertImage(
                    getApplication<Application>().contentResolver,
                    file.absolutePath,
                    outputFileName,
                    "LUT processed image"
                )
                file.absolutePath
            }
            
            outputFile
        } catch (e: Exception) {
            Log.e("DashboardViewModel", "保存图片失败: ${e.message}", e)
            e.printStackTrace()
            ""
        }
    }
    
    // 添加获取文件名的辅助方法
    private fun getFileNameFromUri(uri: Uri): String {
        return try {
            val cursor = getApplication<Application>().contentResolver.query(
                uri, arrayOf(android.provider.MediaStore.Images.Media.DISPLAY_NAME), null, null, null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.MediaStore.Images.Media.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        it.getString(nameIndex) ?: "unknown.jpg"
                    } else {
                        "unknown.jpg"
                    }
                } else {
                    "unknown.jpg"
                }
            } ?: "unknown.jpg"
        } catch (e: Exception) {
            "unknown.jpg"
        }
    }
    
    // 添加URI权限检查方法
    private fun hasUriPermission(uri: Uri): Boolean {
        return try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            getApplication<Application>().contentResolver.takePersistableUriPermission(uri, flags)
            true
        } catch (e: Exception) {
            false
        }
    }
    
    // 添加EXIF数据复制方法
    private fun copyExifData(source: ExifInterface, target: ExifInterface) {
        val attributes = arrayOf(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_APERTURE_VALUE,
            ExifInterface.TAG_ISO_SPEED_RATINGS
        )
        
        for (attribute in attributes) {
            val value = source.getAttribute(attribute)
            if (value != null) {
                target.setAttribute(attribute, value)
            }
        }
    }
    
    // 修复addProcessingRecord方法中的fileName处理
    private fun addProcessingRecord(
        inputPath: String, 
        status: String, 
        outputPath: String, 
        lutFileName: String = "未知",
        params: LutProcessor.ProcessingParams? = null
    ) {
        // 从URI中提取文件名
        val fileName = try {
            when {
                inputPath.startsWith("content://") -> {
                    // 对于content URI，尝试通过ContentResolver获取文件名
                    val uri = Uri.parse(inputPath)
                    getFileNameFromUri(uri)
                }
                else -> {
                    // 对于文件路径，直接提取文件名
                    inputPath.substringAfterLast("/").substringAfterLast("\\")
                }
            }
        } catch (e: Exception) {
            "未知文件"
        }
        
        val record = ProcessingRecord(
            timestamp = System.currentTimeMillis(),
            fileName = fileName, // 使用提取的文件名而不是完整路径
            inputPath = inputPath,
            outputPath = outputPath,
            status = status,
            lutFileName = lutFileName,
            // 修复：params.strength是0-100的整数，需要转换为0-1的浮点数
            strength = (params?.strength ?: 0) / 100f,
            quality = params?.quality ?: 0,
            ditherType = when (params?.ditherType) {
                LutProcessor.DitherType.FLOYD_STEINBERG -> "Floyd"
                LutProcessor.DitherType.RANDOM -> "Random"
                LutProcessor.DitherType.NONE -> "None"
                else -> "None"
            }
        )
        
        val currentList = _processingHistory.value?.toMutableList() ?: mutableListOf()
        currentList.add(0, record)
        _processingHistory.value = currentList
        
        saveProcessingRecord(record)
    }
    
    // 修改saveProcessingRecord方法以支持新的字段
    private fun saveProcessingRecord(record: ProcessingRecord) {
        val prefs = getApplication<Application>().getSharedPreferences("processing_history", android.content.Context.MODE_PRIVATE)
        val existingRecords = prefs.getStringSet("records", emptySet())?.toMutableSet() ?: mutableSetOf()
        
        // 包含所有参数的完整格式
        val recordString = "${record.timestamp}|${record.fileName}|${record.inputPath}|${record.outputPath}|${record.status}|${record.lutFileName}|${record.strength}|${record.quality}|${record.ditherType}"
        existingRecords.add(recordString)
        
        prefs.edit().putStringSet("records", existingRecords).apply()
    }
    
    fun stopProcessing() {
        processingJob?.cancel()
        setProcessing(false)
        updateStatus("处理已停止")
    }

    override fun onCleared() {
        super.onCleared()
        processingJob?.cancel()
    }

    private val _text = MutableLiveData<String>().apply {
        value = "This is dashboard Fragment"
    }
    val text: LiveData<String> = _text
}