package cn.alittlecookie.lut2photo.lut2photo.lut

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import cn.alittlecookie.lut2photo.lut2photo.core.CpuLutProcessor
import cn.alittlecookie.lut2photo.lut2photo.core.ILutProcessor
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * LUT处理器
 * 提供文件到文件的LUT处理功能
 */
class LutProcessor {

    companion object {
        private const val TAG = "LutProcessor"
    }

    /**
     * 处理图片文件
     * @param inputPath 输入图片路径
     * @param outputPath 输出图片路径
     * @param lutPath LUT文件路径
     * @return 是否处理成功
     */
    fun processImage(inputPath: String, outputPath: String, lutPath: String): Boolean {
        return try {
            Log.d(TAG, "开始处理图片: $inputPath -> $outputPath, LUT: $lutPath")

            // 检查输入文件
            val inputFile = File(inputPath)
            if (!inputFile.exists()) {
                Log.e(TAG, "输入文件不存在: $inputPath")
                return false
            }

            // 检查LUT文件
            val lutFile = File(lutPath)
            if (!lutFile.exists()) {
                Log.e(TAG, "LUT文件不存在: $lutPath")
                return false
            }

            // 加载输入图片
            val inputBitmap = BitmapFactory.decodeFile(inputPath)
            if (inputBitmap == null) {
                Log.e(TAG, "无法加载输入图片: $inputPath")
                return false
            }

            // 创建LUT处理器
            val processor = CpuLutProcessor()

            // 使用runBlocking处理协程调用
            val processedBitmap = runBlocking {
                // 加载LUT文件
                FileInputStream(lutFile).use { inputStream ->
                    val success = processor.loadCubeLut(inputStream)
                    if (!success) {
                        Log.e(TAG, "LUT文件加载失败: $lutPath")
                        return@runBlocking null
                    }
                }

                // 创建处理参数
                val params = ILutProcessor.ProcessingParams(
                    strength = 1.0f,
                    lut2Strength = 0f,
                    quality = 95,
                    ditherType = ILutProcessor.DitherType.NONE
                )

                // 处理图片
                processor.processImage(inputBitmap, params)
            }

            if (processedBitmap == null) {
                Log.e(TAG, "图片处理失败")
                return false
            }

            // 确保输出目录存在
            val outputFile = File(outputPath)
            outputFile.parentFile?.mkdirs()

            // 保存处理后的图片
            FileOutputStream(outputFile).use { outputStream ->
                val success = processedBitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    95,
                    outputStream
                )
                if (!success) {
                    Log.e(TAG, "保存图片失败: $outputPath")
                    return false
                }
            }

            // 清理资源
            runBlocking {
                processor.release()
            }
            inputBitmap.recycle()
            processedBitmap.recycle()

            Log.d(TAG, "图片处理完成: $outputPath")
            true

        } catch (e: Exception) {
            Log.e(TAG, "处理图片时发生错误", e)
            false
        }
    }
}