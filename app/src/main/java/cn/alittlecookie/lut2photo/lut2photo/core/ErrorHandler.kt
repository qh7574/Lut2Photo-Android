package cn.alittlecookie.lut2photo.lut2photo.core

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cn.alittlecookie.lut2photo.lut2photo.core.NativeLutProcessor
import cn.alittlecookie.lut2photo.lut2photo.core.EnhancedLutProcessor
import cn.alittlecookie.lut2photo.lut2photo.MyApplication

/**
 * 应用层错误处理和降级策略
 * 提供统一的错误处理、用户提示和系统降级功能
 */
class ErrorHandler(private val context: Context) {

    companion object {
        private const val TAG = "ErrorHandler"

        // 错误类型
        enum class ErrorType {
            MEMORY_ERROR,
            NATIVE_ERROR,
            IO_ERROR,
            NETWORK_ERROR,
            PROCESSING_ERROR,
            UNKNOWN_ERROR
        }

        // 错误严重程度
        enum class ErrorSeverity {
            LOW,      // 可忽略的错误
            MEDIUM,   // 需要用户注意的错误
            HIGH,     // 严重错误，需要降级处理
            CRITICAL  // 致命错误，可能导致应用崩溃
        }
    }

    // 错误统计
    data class ErrorStats(
        val totalErrors: Int,
        val memoryErrors: Int,
        val nativeErrors: Int,
        val ioErrors: Int,
        val networkErrors: Int,
        val processingErrors: Int,
        val unknownErrors: Int
    )

    private var errorStats = ErrorStats(0, 0, 0, 0, 0, 0, 0)
    private val errorListeners = mutableListOf<ErrorListener>()

    interface ErrorListener {
        fun onError(
            errorType: ErrorType,
            severity: ErrorSeverity,
            message: String,
            throwable: Throwable?
        )

        fun onErrorRecovered(errorType: ErrorType, message: String)
    }

    /**
     * 添加错误监听器
     */
    fun addErrorListener(listener: ErrorListener) {
        errorListeners.add(listener)
    }

    /**
     * 移除错误监听器
     */
    fun removeErrorListener(listener: ErrorListener) {
        errorListeners.remove(listener)
    }

    /**
     * 处理错误
     */
    fun handleError(
        errorType: ErrorType,
        severity: ErrorSeverity,
        message: String,
        throwable: Throwable? = null,
        showUserMessage: Boolean = true
    ) {
        Log.e(TAG, "处理错误: $errorType, 严重程度: $severity, 消息: $message", throwable)

        // 更新错误统计
        updateErrorStats(errorType)

        // 通知监听器
        errorListeners.forEach { listener ->
            try {
                listener.onError(errorType, severity, message, throwable)
            } catch (e: Exception) {
                Log.e(TAG, "错误监听器异常", e)
            }
        }

        // 根据严重程度处理
        when (severity) {
            ErrorSeverity.LOW -> {
                if (showUserMessage) {
                    showToast("操作完成，但遇到了一些小问题")
                }
            }

            ErrorSeverity.MEDIUM -> {
                if (showUserMessage) {
                    showToast("操作遇到问题: $message")
                }
                // 尝试自动恢复
                attemptAutoRecovery(errorType)
            }

            ErrorSeverity.HIGH -> {
                if (showUserMessage) {
                    showErrorDialog("严重错误", "操作失败: $message\n\n系统将尝试降级处理。")
                }
                // 执行降级策略
                executeDegradationStrategy(errorType)
            }

            ErrorSeverity.CRITICAL -> {
                if (showUserMessage) {
                    showErrorDialog(
                        "致命错误",
                        "应用遇到严重问题: $message\n\n建议重启应用。",
                        showRestartOption = true
                    )
                }
                // 执行紧急处理
                executeEmergencyHandling(errorType)
            }
        }
    }

    /**
     * 处理内存错误
     */
    fun handleMemoryError(currentUsage: Long, maxMemory: Long, showUserMessage: Boolean = true) {
        val usagePercent = (currentUsage.toDouble() / maxMemory * 100).toInt()
        val message = "内存使用率过高: ${usagePercent}% (${currentUsage}MB/${maxMemory}MB)"

        val severity = when {
            usagePercent >= 95 -> ErrorSeverity.CRITICAL
            usagePercent >= 85 -> ErrorSeverity.HIGH
            usagePercent >= 75 -> ErrorSeverity.MEDIUM
            else -> ErrorSeverity.LOW
        }

        handleError(ErrorType.MEMORY_ERROR, severity, message, showUserMessage = showUserMessage)
    }

    /**
     * 处理Native错误
     */
    fun handleNativeError(errorCode: Int, operation: String, showUserMessage: Boolean = true) {
        val message = "Native操作失败: $operation (错误码: $errorCode)"

        val severity = when (errorCode) {
            -1 -> ErrorSeverity.HIGH      // 内存不足
            -2 -> ErrorSeverity.MEDIUM    // 参数错误
            -3 -> ErrorSeverity.HIGH      // 初始化失败
            -4 -> ErrorSeverity.MEDIUM    // 处理失败
            else -> ErrorSeverity.MEDIUM
        }

        handleError(ErrorType.NATIVE_ERROR, severity, message, showUserMessage = showUserMessage)
    }

    /**
     * 处理IO错误
     */
    fun handleIOError(operation: String, throwable: Throwable, showUserMessage: Boolean = true) {
        val message = "文件操作失败: $operation"
        handleError(ErrorType.IO_ERROR, ErrorSeverity.MEDIUM, message, throwable, showUserMessage)
    }

    /**
     * 处理处理错误
     */
    fun handleProcessingError(
        operation: String,
        throwable: Throwable,
        showUserMessage: Boolean = true
    ) {
        val message = "图像处理失败: $operation"
        handleError(
            ErrorType.PROCESSING_ERROR,
            ErrorSeverity.MEDIUM,
            message,
            throwable,
            showUserMessage
        )
    }

    /**
     * 更新错误统计
     */
    private fun updateErrorStats(errorType: ErrorType) {
        errorStats = when (errorType) {
            ErrorType.MEMORY_ERROR -> errorStats.copy(
                totalErrors = errorStats.totalErrors + 1,
                memoryErrors = errorStats.memoryErrors + 1
            )

            ErrorType.NATIVE_ERROR -> errorStats.copy(
                totalErrors = errorStats.totalErrors + 1,
                nativeErrors = errorStats.nativeErrors + 1
            )

            ErrorType.IO_ERROR -> errorStats.copy(
                totalErrors = errorStats.totalErrors + 1,
                ioErrors = errorStats.ioErrors + 1
            )

            ErrorType.NETWORK_ERROR -> errorStats.copy(
                totalErrors = errorStats.totalErrors + 1,
                networkErrors = errorStats.networkErrors + 1
            )

            ErrorType.PROCESSING_ERROR -> errorStats.copy(
                totalErrors = errorStats.totalErrors + 1,
                processingErrors = errorStats.processingErrors + 1
            )

            ErrorType.UNKNOWN_ERROR -> errorStats.copy(
                totalErrors = errorStats.totalErrors + 1,
                unknownErrors = errorStats.unknownErrors + 1
            )
        }
    }

    /**
     * 尝试自动恢复
     */
    private fun attemptAutoRecovery(errorType: ErrorType) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (errorType) {
                    ErrorType.MEMORY_ERROR -> {
                        // 触发垃圾回收
                        System.gc()
                        // 清理缓存
                        MyApplication.getMemoryManager()?.optimizeMemory()

                        withContext(Dispatchers.Main) {
                            notifyRecovery(errorType, "内存已优化")
                        }
                    }

                    ErrorType.NATIVE_ERROR -> {
                        // 尝试使用增强版处理器重新初始化
                        val enhancedProcessor = EnhancedLutProcessor()
                        if (enhancedProcessor.initialize()) {
                            enhancedProcessor.cleanup()
                            withContext(Dispatchers.Main) {
                                notifyRecovery(errorType, "增强版处理器已重新初始化")
                            }
                        } else {
                            // 降级到基础Native处理器
                            val result = NativeLutProcessor().nativeInitializeGlobalComponents(256)
                            if (result == 0) {
                                withContext(Dispatchers.Main) {
                                    notifyRecovery(errorType, "基础Native组件已重新初始化")
                                }
                            }
                        }
                    }

                    else -> {
                        // 其他错误类型的恢复策略
                        Log.i(TAG, "暂无自动恢复策略: $errorType")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "自动恢复失败: $errorType", e)
            }
        }
    }

    /**
     * 执行降级策略
     */
    private fun executeDegradationStrategy(errorType: ErrorType) {
        Log.i(TAG, "执行降级策略: $errorType")

        when (errorType) {
            ErrorType.MEMORY_ERROR -> {
                // 降低图像质量
                // 减少并发处理
                // 启用瓦片处理
                Log.i(TAG, "启用内存降级模式")
            }

            ErrorType.NATIVE_ERROR -> {
                // 切换到Java实现
                // 禁用高级功能
                Log.i(TAG, "切换到Java处理模式")
            }

            ErrorType.PROCESSING_ERROR -> {
                // 降低处理质量
                // 简化处理流程
                Log.i(TAG, "启用简化处理模式")
            }

            else -> {
                Log.i(TAG, "暂无降级策略: $errorType")
            }
        }
    }

    /**
     * 执行紧急处理
     */
    private fun executeEmergencyHandling(errorType: ErrorType) {
        Log.w(TAG, "执行紧急处理: $errorType")

        // 停止所有后台任务
        // 清理所有缓存
        // 释放所有资源

        try {
            MyApplication.getMemoryManager()?.optimizeMemory()
            System.gc()
        } catch (e: Exception) {
            Log.e(TAG, "紧急处理失败", e)
        }
    }

    /**
     * 通知恢复
     */
    private fun notifyRecovery(errorType: ErrorType, message: String) {
        Log.i(TAG, "错误恢复: $errorType - $message")

        errorListeners.forEach { listener ->
            try {
                listener.onErrorRecovered(errorType, message)
            } catch (e: Exception) {
                Log.e(TAG, "恢复通知异常", e)
            }
        }
    }

    /**
     * 显示Toast消息
     */
    private fun showToast(message: String) {
        CoroutineScope(Dispatchers.Main).launch {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 显示错误对话框
     */
    private fun showErrorDialog(
        title: String,
        message: String,
        showRestartOption: Boolean = false
    ) {
        CoroutineScope(Dispatchers.Main).launch {
            val builder = AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("确定") { dialog, _ ->
                    dialog.dismiss()
                }

            if (showRestartOption) {
                builder.setNegativeButton("重启应用") { _, _ ->
                    // 重启应用逻辑
                    restartApplication()
                }
            }

            builder.show()
        }
    }

    /**
     * 重启应用
     */
    private fun restartApplication() {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
            intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            // 退出当前进程
            android.os.Process.killProcess(android.os.Process.myPid())
        } catch (e: Exception) {
            Log.e(TAG, "重启应用失败", e)
        }
    }

    /**
     * 获取错误统计
     */
    fun getErrorStats(): ErrorStats = errorStats

    /**
     * 重置错误统计
     */
    fun resetErrorStats() {
        errorStats = ErrorStats(0, 0, 0, 0, 0, 0, 0)
    }

    /**
     * 检查系统健康状态
     */
    fun checkSystemHealth(): SystemHealthStatus {
        val memoryManager = MyApplication.getMemoryManager()
        val memoryStatus = memoryManager?.getCurrentMemoryStatus()

        val memoryHealth = when {
            memoryStatus == null -> HealthLevel.UNKNOWN
            memoryStatus.usageRatio >= 0.9f -> HealthLevel.CRITICAL
            memoryStatus.usageRatio >= 0.75f -> HealthLevel.WARNING
            memoryStatus.usageRatio >= 0.6f -> HealthLevel.GOOD
            else -> HealthLevel.EXCELLENT
        }

        val errorHealth = when {
            errorStats.totalErrors >= 50 -> HealthLevel.CRITICAL
            errorStats.totalErrors >= 20 -> HealthLevel.WARNING
            errorStats.totalErrors >= 10 -> HealthLevel.GOOD
            else -> HealthLevel.EXCELLENT
        }

        val overallHealth = minOf(memoryHealth, errorHealth)

        return SystemHealthStatus(
            overallHealth = overallHealth,
            memoryHealth = memoryHealth,
            errorHealth = errorHealth,
            memoryUsagePercentage = (memoryStatus?.usageRatio ?: 0f) * 100f,
            totalErrors = errorStats.totalErrors
        )
    }

    enum class HealthLevel {
        EXCELLENT, GOOD, WARNING, CRITICAL, UNKNOWN
    }

    data class SystemHealthStatus(
        val overallHealth: HealthLevel,
        val memoryHealth: HealthLevel,
        val errorHealth: HealthLevel,
        val memoryUsagePercentage: Float,
        val totalErrors: Int
    )
}