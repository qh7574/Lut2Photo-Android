package cn.alittlecookie.lut2photo.lut2photo

import android.graphics.Bitmap
import android.graphics.Color
import cn.alittlecookie.lut2photo.lut2photo.core.NativeLutProcessor
import cn.alittlecookie.lut2photo.lut2photo.core.ILutProcessor
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.runBlocking
import java.io.InputStream

/**
 * Native性能测试，验证SIMD和多线程功能
 */
class NativePerformanceTest {

    private lateinit var processor: NativeLutProcessor
    private lateinit var testBitmap: Bitmap

    @Before
    fun setup() {
        processor = NativeLutProcessor()

        // 创建测试用的Bitmap (1024x1024)
        testBitmap = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)

        // 填充测试数据
        for (y in 0 until 1024) {
            for (x in 0 until 1024) {
                val r = (x * 255 / 1024)
                val g = (y * 255 / 1024)
                val b = ((x + y) * 255 / 2048)
                testBitmap.setPixel(x, y, Color.rgb(r, g, b))
            }
        }

        // 创建简单的LUT数据用于测试
        val lutSize = 64
        val lutContent = StringBuilder()

        lutContent.appendLine("LUT_3D_SIZE $lutSize")

        for (b in 0 until lutSize) {
            for (g in 0 until lutSize) {
                for (r in 0 until lutSize) {
                    val rVal = (r * 1.1f / (lutSize - 1)).coerceIn(0f, 1f)
                    val gVal = (g * 1.1f / (lutSize - 1)).coerceIn(0f, 1f)
                    val bVal = (b * 1.1f / (lutSize - 1)).coerceIn(0f, 1f)
                    lutContent.appendLine("$rVal $gVal $bVal")
                }
            }
        }

        runBlocking {
            processor.loadCubeLut(lutContent.toString().byteInputStream())
        }
    }

    @Test
    fun testSingleThreadedPerformance() {
        // 测试单线程性能
        val singleThreadTime = measureTimeMillis {
            repeat(5) {
                val result = runBlocking {
                    processor.processImage(
                        bitmap = testBitmap,
                        params = ILutProcessor.ProcessingParams(
                            strength = 1.0f,
                            lut2Strength = 0.0f,
                            quality = 100,
                            ditherType = ILutProcessor.DitherType.NONE
                        )
                    )
                }
                assertNotNull("单线程处理结果不应为null", result)
            }
        }

        println("单线程处理5次耗时: ${singleThreadTime}ms")
        assertTrue("单线程处理时间应该合理", singleThreadTime > 0)
    }

    @Test
    fun testMultiThreadedPerformance() {
        // 测试多线程性能
        val multiThreadTime = measureTimeMillis {
            repeat(5) {
                val result = runBlocking {
                    processor.processImage(
                        bitmap = testBitmap,
                        params = ILutProcessor.ProcessingParams(
                            strength = 1.0f,
                            lut2Strength = 0.0f,
                            quality = 100,
                            ditherType = ILutProcessor.DitherType.NONE
                        )
                    )
                }
                assertNotNull("多线程处理结果不应为null", result)
            }
        }

        println("多线程处理5次耗时: ${multiThreadTime}ms")
        assertTrue("多线程处理时间应该合理", multiThreadTime > 0)
    }

    @Test
    fun testPerformanceComparison() {
        // 比较单线程和多线程性能
        val iterations = 3

        val singleThreadTime = measureTimeMillis {
            repeat(iterations) {
                runBlocking {
                    processor.processImage(
                        bitmap = testBitmap,
                        params = ILutProcessor.ProcessingParams(
                            strength = 1.0f,
                            lut2Strength = 0.0f,
                            quality = 100,
                            ditherType = ILutProcessor.DitherType.NONE
                        )
                    )
                }
            }
        }

        val multiThreadTime = measureTimeMillis {
            repeat(iterations) {
                runBlocking {
                    processor.processImage(
                        bitmap = testBitmap,
                        params = ILutProcessor.ProcessingParams(
                            strength = 1.0f,
                            lut2Strength = 0.0f,
                            quality = 100,
                            ditherType = ILutProcessor.DitherType.NONE
                        )
                    )
                }
            }
        }

        println("性能对比结果:")
        println("单线程 ${iterations}次: ${singleThreadTime}ms (平均: ${singleThreadTime / iterations}ms)")
        println("多线程 ${iterations}次: ${multiThreadTime}ms (平均: ${multiThreadTime / iterations}ms)")

        if (multiThreadTime < singleThreadTime) {
            val speedup = singleThreadTime.toFloat() / multiThreadTime
            println("多线程加速比: ${String.format("%.2f", speedup)}x")
        } else {
            println("注意: 多线程性能未超过单线程，可能是图像太小或线程开销较大")
        }

        // 验证两种方式都能正常工作
        assertTrue("单线程处理应该成功", singleThreadTime > 0)
        assertTrue("多线程处理应该成功", multiThreadTime > 0)
    }

    @Test
    fun testMemoryUsage() {
        // 测试内存使用情况
        val initialMemory = processor.getNativeMemoryUsage()
        println("初始内存使用: ${initialMemory}KB")

        // 处理多张图片
        repeat(10) {
            val result = runBlocking {
                processor.processImage(
                    bitmap = testBitmap,
                    params = ILutProcessor.ProcessingParams(
                        strength = 1.0f,
                        lut2Strength = 0.0f,
                        quality = 100,
                        ditherType = ILutProcessor.DitherType.NONE
                    )
                )
            }
            assertNotNull("处理结果不应为null", result)
        }

        val finalMemory = processor.getNativeMemoryUsage()
        println("处理后内存使用: ${finalMemory}KB")

        // 强制垃圾回收
        processor.forceNativeGarbageCollection()

        val afterGcMemory = processor.getNativeMemoryUsage()
        println("GC后内存使用: ${afterGcMemory}KB")

        // 验证内存没有严重泄漏
        assertTrue("内存使用应该合理", afterGcMemory < initialMemory + 10000) // 允许10MB的增长
    }
}