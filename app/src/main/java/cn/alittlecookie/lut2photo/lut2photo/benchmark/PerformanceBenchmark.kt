package cn.alittlecookie.lut2photo.lut2photo.benchmark

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import cn.alittlecookie.lut2photo.lut2photo.core.ILutProcessor
import cn.alittlecookie.lut2photo.lut2photo.core.NativeLutProcessor
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import kotlin.system.measureTimeMillis

/**
 * 性能基准测试类
 * 用于验证SIMD和多线程功能的性能表现
 */
class PerformanceBenchmark {

    companion object {
        private const val TAG = "PerformanceBenchmark"

        /**
         * 运行完整的性能基准测试
         */
        fun runFullBenchmark(): String {
            val results = StringBuilder()
            results.appendLine("=== Native LUT处理器性能基准测试 ===")

            try {
                // 创建处理器实例
                val processor = NativeLutProcessor()

                // 检查处理器可用性
                val isAvailable = runBlocking { processor.isAvailable() }
                results.appendLine("处理器状态: ${if (isAvailable) "可用" else "不可用"}")

                if (!isAvailable) {
                    results.appendLine("错误: Native处理器不可用，无法进行性能测试")
                    return results.toString()
                }

                // 加载测试LUT
                val lutLoaded = loadTestLut(processor)
                results.appendLine("LUT加载: ${if (lutLoaded) "成功" else "失败"}")

                if (!lutLoaded) {
                    results.appendLine("错误: LUT加载失败，无法进行性能测试")
                    return results.toString()
                }

                // 运行各项性能测试
                results.appendLine("\n--- 图像处理性能测试 ---")
                results.append(testImageProcessingPerformance(processor))

                results.appendLine("\n--- 内存使用测试 ---")
                results.append(testMemoryUsage(processor))

                results.appendLine("\n--- 处理器信息 ---")
                results.appendLine(processor.getProcessorInfo())

                // 清理资源
                runBlocking { processor.release() }
                results.appendLine("\n资源清理完成")

            } catch (e: Exception) {
                results.appendLine("基准测试异常: ${e.message}")
                Log.e(TAG, "基准测试失败", e)
            }

            return results.toString()
        }

        /**
         * 加载测试LUT数据
         */
        private fun loadTestLut(processor: NativeLutProcessor): Boolean {
            return try {
                val lutContent = createTestLutContent()
                val inputStream = ByteArrayInputStream(lutContent.toByteArray())
                runBlocking { processor.loadCubeLut(inputStream) }
            } catch (e: Exception) {
                Log.e(TAG, "加载测试LUT失败", e)
                false
            }
        }

        /**
         * 创建测试LUT内容
         */
        private fun createTestLutContent(): String {
            val lutSize = 32 // 使用较小的LUT以提高测试速度
            val content = StringBuilder()

            content.appendLine("LUT_3D_SIZE $lutSize")
            content.appendLine("# Test LUT for performance benchmark")

            for (b in 0 until lutSize) {
                for (g in 0 until lutSize) {
                    for (r in 0 until lutSize) {
                        val rVal = (r.toFloat() / (lutSize - 1) * 1.1f).coerceIn(0f, 1f)
                        val gVal = (g.toFloat() / (lutSize - 1) * 1.1f).coerceIn(0f, 1f)
                        val bVal = (b.toFloat() / (lutSize - 1) * 1.1f).coerceIn(0f, 1f)
                        content.appendLine("$rVal $gVal $bVal")
                    }
                }
            }

            return content.toString()
        }

        /**
         * 测试图像处理性能
         */
        private fun testImageProcessingPerformance(processor: NativeLutProcessor): String {
            val results = StringBuilder()

            try {
                // 测试不同尺寸的图像
                val testSizes = listOf(
                    Pair(512, 512),
                    Pair(1024, 1024),
                    Pair(2048, 2048)
                )

                for ((width, height) in testSizes) {
                    results.appendLine("\n测试图像尺寸: ${width}x${height}")

                    // 创建测试图像
                    val testBitmap = createTestBitmap(width, height)

                    // 创建处理参数
                    val params = ILutProcessor.ProcessingParams(
                        strength = 1.0f,
                        lut2Strength = 0.0f,
                        quality = 90,
                        ditherType = ILutProcessor.DitherType.NONE
                    )

                    // 执行性能测试
                    val iterations = if (width <= 1024) 5 else 3
                    var totalTime = 0L
                    var successCount = 0

                    for (i in 1..iterations) {
                        val processingTime = measureTimeMillis {
                            runBlocking {
                                val result = processor.processImage(testBitmap, params)
                                if (result != null) {
                                    successCount++
                                    result.recycle()
                                }
                            }
                        }
                        totalTime += processingTime
                        results.appendLine("  第${i}次: ${processingTime}ms")
                    }

                    val avgTime = totalTime / iterations
                    val pixelCount = width * height
                    val pixelsPerSecond = (pixelCount * 1000.0 / avgTime).toLong()

                    results.appendLine("  平均处理时间: ${avgTime}ms")
                    results.appendLine("  成功次数: $successCount/$iterations")
                    results.appendLine("  处理速度: ${pixelsPerSecond} 像素/秒")

                    testBitmap.recycle()
                }

            } catch (e: Exception) {
                results.appendLine("图像处理性能测试异常: ${e.message}")
                Log.e(TAG, "图像处理性能测试失败", e)
            }

            return results.toString()
        }

        /**
         * 测试内存使用情况
         */
        private fun testMemoryUsage(processor: NativeLutProcessor): String {
            val results = StringBuilder()

            try {
                // 获取初始内存使用
                val initialMemory = processor.getNativeMemoryUsage()
                results.appendLine("初始Native内存使用: ${initialMemory / 1024 / 1024}MB")

                // 创建测试图像并处理
                val testBitmap = createTestBitmap(1024, 1024)
                val params = ILutProcessor.ProcessingParams(
                    strength = 1.0f,
                    lut2Strength = 0.0f,
                    quality = 90,
                    ditherType = ILutProcessor.DitherType.NONE
                )

                // 处理图像并监控内存
                runBlocking {
                    val result = processor.processImage(testBitmap, params)
                    val processingMemory = processor.getNativeMemoryUsage()
                    results.appendLine("处理时Native内存使用: ${processingMemory / 1024 / 1024}MB")

                    result?.recycle()
                }

                // 强制垃圾回收
                processor.forceNativeGarbageCollection()
                System.gc()
                Thread.sleep(100) // 等待GC完成

                val finalMemory = processor.getNativeMemoryUsage()
                results.appendLine("GC后Native内存使用: ${finalMemory / 1024 / 1024}MB")

                // 检查内存限制
                val isNearLimit = processor.isNearMemoryLimit(0.8f)
                results.appendLine("是否接近内存限制: ${if (isNearLimit) "是" else "否"}")

                testBitmap.recycle()

            } catch (e: Exception) {
                results.appendLine("内存使用测试异常: ${e.message}")
                Log.e(TAG, "内存使用测试失败", e)
            }

            return results.toString()
        }

        /**
         * 创建测试用的Bitmap
         */
        private fun createTestBitmap(width: Int, height: Int): Bitmap {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            // 填充渐变色彩
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val r = (x * 255 / width)
                    val g = (y * 255 / height)
                    val b = ((x + y) * 255 / (width + height))
                    pixels[y * width + x] = Color.rgb(r, g, b)
                }
            }

            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            return bitmap
        }
    }
}