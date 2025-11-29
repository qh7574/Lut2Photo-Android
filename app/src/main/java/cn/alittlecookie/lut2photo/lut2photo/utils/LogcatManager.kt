package cn.alittlecookie.lut2photo.lut2photo.utils

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogcatManager(private val context: Context) {
    
    companion object {
        private const val TAG = "LogcatManager"
        private const val LOGCAT_DIR = "logcat"
    }
    
    private var logcatProcess: Process? = null
    private var logcatJob: Job? = null
    private val logcatScope = CoroutineScope(Dispatchers.IO)
    private var currentLogFile: File? = null
    
    /**
     * 开始捕获 Logcat
     */
    fun startCapture() {
        if (logcatJob?.isActive == true) {
            Log.w(TAG, "Logcat 捕获已在运行")
            return
        }
        
        logcatJob = logcatScope.launch {
            try {
                // 创建日志文件
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val logDir = File(context.getExternalFilesDir(null), LOGCAT_DIR)
                if (!logDir.exists()) {
                    logDir.mkdirs()
                }
                
                currentLogFile = File(logDir, "Lut2Photo-logcat-$timestamp.txt")
                
                // 写入系统信息
                writeSystemInfo(currentLogFile!!)
                
                // 清除旧的 logcat 缓冲区
                Runtime.getRuntime().exec("logcat -c").waitFor()
                
                // 启动 logcat 进程
                logcatProcess = Runtime.getRuntime().exec(
                    arrayOf("logcat", "-v", "time", "*:V")
                )
                
                val reader = BufferedReader(InputStreamReader(logcatProcess!!.inputStream))
                val writer = FileWriter(currentLogFile, true)
                
                writer.use { w ->
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        w.write(line)
                        w.write("\n")
                        w.flush()
                    }
                }
                
                Log.d(TAG, "Logcat 捕获已停止")
            } catch (e: Exception) {
                Log.e(TAG, "Logcat 捕获失败", e)
            }
        }
        
        Log.d(TAG, "Logcat 捕获已启动")
    }
    
    /**
     * 停止捕获并删除所有日志文件
     */
    fun stopCaptureAndClean() {
        stopCapture()
        deleteAllLogFiles()
    }
    
    /**
     * 停止捕获
     */
    private fun stopCapture() {
        logcatJob?.cancel()
        logcatJob = null
        
        logcatProcess?.destroy()
        logcatProcess = null
        
        Log.d(TAG, "Logcat 捕获已停止")
    }
    
    /**
     * 获取最新的日志文件
     */
    fun getLatestLogFile(): File? {
        val logDir = File(context.getExternalFilesDir(null), LOGCAT_DIR)
        if (!logDir.exists()) return null
        
        return logDir.listFiles()
            ?.filter { it.name.startsWith("Lut2Photo-logcat-") && it.name.endsWith(".txt") }
            ?.maxByOrNull { it.lastModified() }
    }
    
    /**
     * 删除所有日志文件
     */
    private fun deleteAllLogFiles() {
        val logDir = File(context.getExternalFilesDir(null), LOGCAT_DIR)
        if (!logDir.exists()) return
        
        logDir.listFiles()
            ?.filter { it.name.startsWith("Lut2Photo-logcat-") && it.name.endsWith(".txt") }
            ?.forEach { it.delete() }
        
        Log.d(TAG, "所有日志文件已删除")
    }
    
    /**
     * 写入系统信息
     */
    private fun writeSystemInfo(file: File) {
        try {
            FileWriter(file, false).use { writer ->
                writer.write("=".repeat(60) + "\n")
                writer.write("Lut2Photo 日志文件\n")
                writer.write("生成时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
                writer.write("=".repeat(60) + "\n\n")
                
                writer.write("系统信息:\n")
                writer.write("-".repeat(60) + "\n")
                writer.write("设备型号: ${Build.MODEL}\n")
                writer.write("设备品牌: ${Build.BRAND}\n")
                writer.write("设备制造商: ${Build.MANUFACTURER}\n")
                writer.write("Android 版本: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
                writer.write("处理器架构: ${Build.SUPPORTED_ABIS.joinToString(", ")}\n")
                
                // 获取内存信息
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val memInfo = android.app.ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memInfo)
                writer.write("总内存: ${memInfo.totalMem / 1024 / 1024} MB\n")
                writer.write("可用内存: ${memInfo.availMem / 1024 / 1024} MB\n")
                writer.write("内存类: ${activityManager.memoryClass} MB\n")
                writer.write("大内存类: ${activityManager.largeMemoryClass} MB\n")
                
                writer.write("-".repeat(60) + "\n\n")
                writer.write("日志开始:\n")
                writer.write("=".repeat(60) + "\n\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "写入系统信息失败", e)
        }
    }
    
    /**
     * 释放资源
     */
    fun release() {
        stopCapture()
        logcatScope.cancel()
    }
}
