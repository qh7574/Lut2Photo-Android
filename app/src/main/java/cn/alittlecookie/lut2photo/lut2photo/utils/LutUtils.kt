package cn.alittlecookie.lut2photo.lut2photo.utils

import android.graphics.Bitmap
import android.util.Log
import cn.alittlecookie.lut2photo.lut2photo.core.CpuLutProcessor
import cn.alittlecookie.lut2photo.lut2photo.core.ILutProcessor
import cn.alittlecookie.lut2photo.lut2photo.gpu.GpuLutProcessor
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream

/**
 * LUT工具类，提供简化的LUT处理接口
 */
object LutUtils {

    private const val TAG = "LutUtils"

    /**
     * 为图片应用LUT效果（CPU处理）
     * @param bitmap 原始图片
     * @param lutPath LUT文件路径
     * @param intensity LUT强度 (0.0 - 1.0)
     * @return 应用LUT后的图片
     */
    fun applyLut(bitmap: Bitmap, lutPath: String, intensity: Float = 1.0f): Bitmap {
        return try {
            val lutFile = File(lutPath)
            if (!lutFile.exists()) {
                Log.w(TAG, "LUT文件不存在: $lutPath")
                return bitmap
            }

            // 创建CPU LUT处理器
            val processor = CpuLutProcessor()

            // 使用runBlocking处理协程调用
            runBlocking {
                // 加载LUT文件
                FileInputStream(lutFile).use { inputStream ->
                    val success = processor.loadCubeLut(inputStream)
                    if (!success) {
                        Log.w(TAG, "LUT文件加载失败: $lutPath")
                        return@runBlocking bitmap
                    }
                }

                // 创建处理参数
                val params = ILutProcessor.ProcessingParams(
                    strength = intensity,
                    lut2Strength = 0f,
                    quality = 95,
                    ditherType = ILutProcessor.DitherType.NONE
                )

                // 应用LUT效果
                val processedBitmap = processor.processImage(bitmap, params)
                processedBitmap ?: bitmap
            }

        } catch (e: Exception) {
            Log.e(TAG, "应用LUT时发生错误: $lutPath", e)
            bitmap
        }
    }

    /**
     * 为图片应用双LUT效果（GPU加速）
     * @param bitmap 原始图片
     * @param lutPath 第一个LUT文件路径
     * @param intensity 第一个LUT强度 (0.0 - 1.0)
     * @param lut2Path 第二个LUT文件路径（可选）
     * @param lut2Intensity 第二个LUT强度 (0.0 - 1.0)
     * @return 应用LUT后的图片
     */
    suspend fun applyDualLutGpu(
        bitmap: Bitmap,
        lutPath: String?,
        intensity: Float = 1.0f,
        lut2Path: String? = null,
        lut2Intensity: Float = 0.0f,
        context: android.content.Context
    ): Bitmap {
        return try {
            Log.d(
                TAG,
                "GPU双LUT处理开始 - 强度参数: intensity=$intensity, lut2Intensity=$lut2Intensity"
            )
            Log.d(TAG, "GPU双LUT处理开始 - LUT路径: lutPath=$lutPath, lut2Path=$lut2Path")

            // 创建GPU LUT处理器
            val processor = GpuLutProcessor(context)

            // 检查GPU是否可用
            val gpuAvailable = processor.isAvailable()
            Log.d(TAG, "GPU可用性检查结果: $gpuAvailable")

            if (!gpuAvailable) {
                Log.w(TAG, "GPU处理器不可用，回退到CPU处理")
                processor.release()

                // 回退到CPU处理
                return applyDualLutCpu(bitmap, lutPath, intensity, lut2Path, lut2Intensity)
            }

            Log.d(TAG, "GPU处理器可用，继续GPU处理")

            // 加载第一个LUT
            if (!lutPath.isNullOrEmpty() && File(lutPath).exists()) {
                FileInputStream(lutPath).use { inputStream ->
                    val success = processor.loadCubeLut(inputStream)
                    if (!success) {
                        Log.w(TAG, "第一个LUT文件加载失败: $lutPath")
                    }
                }
            }

            // 加载第二个LUT
            if (!lut2Path.isNullOrEmpty() && File(lut2Path).exists()) {
                FileInputStream(lut2Path).use { inputStream ->
                    val success = processor.loadSecondCubeLut(inputStream)
                    if (!success) {
                        Log.w(TAG, "第二个LUT文件加载失败: $lut2Path")
                    }
                }
            }

            // 创建处理参数
            val params = ILutProcessor.ProcessingParams(
                strength = intensity,
                lut2Strength = lut2Intensity,
                quality = 95,
                ditherType = ILutProcessor.DitherType.NONE
            )
            Log.d(
                TAG,
                "GPU处理参数创建完成: strength=${params.strength}, lut2Strength=${params.lut2Strength}"
            )

            // 应用LUT效果
            val processedBitmap = processor.processImage(bitmap, params)

            Log.d(TAG, "GPU处理完成")

            // 清理资源
            processor.release()

            processedBitmap ?: bitmap

        } catch (e: Exception) {
            Log.e(
                TAG,
                "GPU应用双LUT时发生错误: lutPath=$lutPath, lut2Path=$lut2Path, intensity=$intensity, lut2Intensity=$lut2Intensity",
                e
            )
            bitmap
        }
    }

    /**
     * 为图片应用双LUT效果（CPU处理）
     * @param bitmap 原始图片
     * @param lutPath 第一个LUT文件路径
     * @param intensity 第一个LUT强度 (0.0 - 1.0)
     * @param lut2Path 第二个LUT文件路径（可选）
     * @param lut2Intensity 第二个LUT强度 (0.0 - 1.0)
     * @return 应用LUT后的图片
     */
    private suspend fun applyDualLutCpu(
        bitmap: Bitmap,
        lutPath: String?,
        intensity: Float = 1.0f,
        lut2Path: String? = null,
        lut2Intensity: Float = 0.0f
    ): Bitmap {
        return try {
            Log.d(TAG, "CPU双LUT处理开始")

            // 创建CPU LUT处理器
            val processor = CpuLutProcessor()

            // 加载第一个LUT
            if (!lutPath.isNullOrEmpty() && File(lutPath).exists()) {
                FileInputStream(lutPath).use { inputStream ->
                    val success = processor.loadCubeLut(inputStream)
                    if (!success) {
                        Log.w(TAG, "第一个LUT文件加载失败: $lutPath")
                    }
                }
            }

            // 加载第二个LUT
            if (!lut2Path.isNullOrEmpty() && File(lut2Path).exists()) {
                FileInputStream(lut2Path).use { inputStream ->
                    val success = processor.loadSecondCubeLut(inputStream)
                    if (!success) {
                        Log.w(TAG, "第二个LUT文件加载失败: $lut2Path")
                    }
                }
            }

            // 创建处理参数
            val params = ILutProcessor.ProcessingParams(
                strength = intensity,
                lut2Strength = lut2Intensity,
                quality = 95,
                ditherType = ILutProcessor.DitherType.NONE
            )

            // 应用LUT效果
            val processedBitmap = processor.processImage(bitmap, params)

            Log.d(TAG, "CPU双LUT处理完成")

            // 清理资源
            processor.release()

            processedBitmap ?: bitmap

        } catch (e: Exception) {
            Log.e(
                TAG,
                "CPU应用双LUT时发生错误: lutPath=$lutPath, lut2Path=$lut2Path, intensity=$intensity, lut2Intensity=$lut2Intensity",
                e
            )
            bitmap
        }
    }
}