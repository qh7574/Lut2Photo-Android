package cn.alittlecookie.lut2photo.lut2photo.core

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.round
import kotlin.random.Random

/**
 * CPU LUT处理器实现
 * 基于原有的LutProcessor重构
 */
class CpuLutProcessor : ILutProcessor {
    // 将正则表达式编译移到类级别，避免重复编译
    companion object {
        // 更宽松的正则表达式，支持更多数字格式
        private val DATA_LINE_REGEX =
            Regex("^\\s*([+-]?[0-9]*\\.?[0-9]+(?:[eE][+-]?[0-9]+)?)\\s+([+-]?[0-9]*\\.?[0-9]+(?:[eE][+-]?[0-9]+)?)\\s+([+-]?[0-9]*\\.?[0-9]+(?:[eE][+-]?[0-9]+)?)\\s*$")
    }

    internal var lut: Array<Array<Array<FloatArray>>>? = null
    internal var lutSize: Int = 0

    /**
     * 获取加载的LUT数据
     * @return LUT数据数组，如果未加载则返回null
     */
    fun getLutData(): Array<Array<Array<FloatArray>>>? {
        return lut
    }

    /**
     * 获取LUT尺寸
     * @return LUT尺寸
     */
    fun getLutSize(): Int {
        return lutSize
    }

    override fun getProcessorType(): ILutProcessor.ProcessorType {
        return ILutProcessor.ProcessorType.CPU
    }

    override suspend fun isAvailable(): Boolean {
        return true // CPU处理器总是可用
    }

    override suspend fun loadCubeLut(inputStream: InputStream): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d("CpuLutProcessor", "开始加载LUT文件")

                // 使用BufferedReader提高读取性能
                val reader = inputStream.bufferedReader()
                val lines = reader.readLines()
                reader.close()

                Log.d("CpuLutProcessor", "读取到 ${lines.size} 行数据")

                var lutSize = 0
                var dataStartIndex = -1

                // 解析头部信息
                for ((index, line) in lines.withIndex()) {
                    val trimmedLine = line.trim()
                    Log.d("CpuLutProcessor", "检查第 ${index + 1} 行: $trimmedLine")

                    if (trimmedLine.startsWith("LUT_3D_SIZE")) {
                        Log.d("CpuLutProcessor", "找到LUT_3D_SIZE行: $trimmedLine")
                        val parts = trimmedLine.split("\\s+".toRegex())
                        if (parts.size >= 2) {
                            lutSize = parts[1].toInt()
                            Log.d("CpuLutProcessor", "LUT尺寸: $lutSize")
                            dataStartIndex = index + 1
                            break
                        }
                    }
                }

                if (lutSize == 0) {
                    Log.e("CpuLutProcessor", "未找到有效的LUT_3D_SIZE")
                    return@withContext false
                }

                // 初始化LUT数组
                val lutArray =
                    Array(lutSize) { Array(lutSize) { Array(lutSize) { FloatArray(3) } } }
                Log.d("CpuLutProcessor", "LUT数组初始化完成")

                // 解析数据行
                var dataIndex = 0
                val expectedSize = lutSize * lutSize * lutSize

                // 在loadCubeLut方法中增强数据解析
                for (i in dataStartIndex until lines.size) {
                    if (dataIndex >= expectedSize) break

                    val line = lines[i].trim()
                    if (line.isEmpty() || line.startsWith("#") || line.startsWith("TITLE") || line.startsWith(
                            "DOMAIN_"
                        )
                    ) {
                        continue
                    }

                    // 尝试多种解析方式
                    val match = DATA_LINE_REGEX.matchEntire(line)
                    if (match != null) {
                        try {
                            val r = match.groupValues[1].toFloat()
                            val g = match.groupValues[2].toFloat()
                            val b = match.groupValues[3].toFloat()

                            val rIndex = dataIndex % lutSize
                            val gIndex = (dataIndex / lutSize) % lutSize
                            val bIndex = dataIndex / (lutSize * lutSize)

                            lutArray[rIndex][gIndex][bIndex][0] = r
                            lutArray[rIndex][gIndex][bIndex][1] = g
                            lutArray[rIndex][gIndex][bIndex][2] = b

                            dataIndex++
                        } catch (e: NumberFormatException) {
                            Log.w("CpuLutProcessor", "无法解析数据行 ${i + 1}: $line", e)
                        }
                    } else {
                        // 尝试简单的空格分割解析
                        val parts = line.split("\\s+".toRegex())
                        if (parts.size >= 3) {
                            try {
                                val r = parts[0].toFloat()
                                val g = parts[1].toFloat()
                                val b = parts[2].toFloat()

                                val rIndex = dataIndex % lutSize
                                val gIndex = (dataIndex / lutSize) % lutSize
                                val bIndex = dataIndex / (lutSize * lutSize)

                                lutArray[rIndex][gIndex][bIndex][0] = r
                                lutArray[rIndex][gIndex][bIndex][1] = g
                                lutArray[rIndex][gIndex][bIndex][2] = b

                                dataIndex++
                                Log.d("CpuLutProcessor", "备用解析成功，行 ${i + 1}: [$r, $g, $b]")
                            } catch (_: NumberFormatException) {
                                Log.w("CpuLutProcessor", "备用解析也失败，跳过行 ${i + 1}: $line")
                            }
                        } else {
                            Log.v("CpuLutProcessor", "跳过非数据行 ${i + 1}: $line")
                        }
                    }
                }

                Log.d(
                    "CpuLutProcessor",
                    "LUT数据加载完成，期望数据点: $expectedSize，实际数据点: $dataIndex"
                )

                if (dataIndex == expectedSize) {
                    this@CpuLutProcessor.lut = lutArray
                    this@CpuLutProcessor.lutSize = lutSize
                    Log.d("CpuLutProcessor", "LUT文件加载成功")
                    true
                } else {
                    Log.e("CpuLutProcessor", "LUT数据不完整: 期望 $expectedSize，实际 $dataIndex")
                    false
                }
            } catch (e: Exception) {
                Log.e("CpuLutProcessor", "加载LUT文件失败", e)
                false
            }
        }
    }

    override suspend fun processImage(
        bitmap: Bitmap,
        params: ILutProcessor.ProcessingParams
    ): Bitmap? = withContext(Dispatchers.Default) {
        if (lut == null) return@withContext null

        try {
            val width = bitmap.width
            val height = bitmap.height

            // 计算分块大小，避免单次处理过大的区域
            val maxBlockSize = 1024 * 1024 // 1M像素为一块
            val totalPixels = width * height

            return@withContext if (totalPixels <= maxBlockSize) {
                // 小图片直接处理
                processImageDirect(bitmap, params)
            } else {
                // 大图片分块处理
                processImageInBlocks(bitmap, params, maxBlockSize)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override suspend fun release() {
        lut = null
        lutSize = 0
    }

    override fun getProcessorInfo(): String {
        return "CPU LUT处理器 - 支持多线程并行处理"
    }

    private fun processImageDirect(
        bitmap: Bitmap,
        params: ILutProcessor.ProcessingParams
    ): Bitmap? {
        Log.d(
            "CpuLutProcessor",
            "开始CPU直接处理，LUT数据: ${if (lut != null) "已加载(${lutSize}x${lutSize}x${lutSize})" else "未加载"}，强度: ${params.strength}"
        )

        if (lut == null) {
            Log.e("CpuLutProcessor", "LUT数据为空，无法处理")
            return null
        }

        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var processedPixels = 0

        // 应用LUT处理
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = Color.red(pixel) / 255f
            val g = Color.green(pixel) / 255f
            val b = Color.blue(pixel) / 255f
            val a = Color.alpha(pixel)

            // 三线性插值
            val lutResult = trilinearInterpolation(r, g, b)

            // 修复强度参数处理 - strength已经是0.0-1.0范围的Float值
            val strength = params.strength.coerceIn(0f, 1f)  // 移除除以100的操作
            val finalR = (r * (1 - strength) + lutResult[0] * strength).coerceIn(0f, 1f)
            val finalG = (g * (1 - strength) + lutResult[1] * strength).coerceIn(0f, 1f)
            val finalB = (b * (1 - strength) + lutResult[2] * strength).coerceIn(0f, 1f)

            pixels[i] = Color.argb(
                a,
                (finalR * 255).toInt(),
                (finalG * 255).toInt(),
                (finalB * 255).toInt()
            )
            processedPixels++

            // 每处理1000个像素记录一次进度
            if (processedPixels % 1000 == 0) {
                Log.v("CpuLutProcessor", "已处理像素: $processedPixels/${pixels.size}")
            }
        }

        // 应用抖动
        when (params.ditherType) {
            ILutProcessor.DitherType.FLOYD_STEINBERG -> {
                Log.d("CpuLutProcessor", "应用Floyd-Steinberg抖动")
                applyFloydSteinbergDithering(pixels, width, height)
            }

            ILutProcessor.DitherType.RANDOM -> {
                Log.d("CpuLutProcessor", "应用随机抖动")
                applyRandomDithering(pixels)
            }

            ILutProcessor.DitherType.NONE -> {
                Log.d("CpuLutProcessor", "不应用抖动")
            }
        }

        val resultBitmap = createBitmap(width, height)
        resultBitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        Log.d("CpuLutProcessor", "CPU处理完成，处理了 $processedPixels 个像素")
        return resultBitmap
    }

    private suspend fun processImageInBlocks(
        bitmap: Bitmap,
        params: ILutProcessor.ProcessingParams,
        maxBlockSize: Int
    ): Bitmap? {
        val width = bitmap.width
        val height = bitmap.height

        // 计算合适的块高度
        val blockHeight = maxBlockSize / width
        val actualBlockHeight = minOf(blockHeight, height)

        // 创建结果bitmap
        val resultBitmap = createBitmap(width, height)

        var currentY = 0
        while (currentY < height) {
            val remainingHeight = height - currentY
            val currentBlockHeight = minOf(actualBlockHeight, remainingHeight)

            // 处理当前块
            val blockPixels = IntArray(width * currentBlockHeight)
            bitmap.getPixels(blockPixels, 0, width, 0, currentY, width, currentBlockHeight)

            // 应用LUT处理到当前块
            for (i in blockPixels.indices) {
                val pixel = blockPixels[i]
                val r = Color.red(pixel) / 255f
                val g = Color.green(pixel) / 255f
                val b = Color.blue(pixel) / 255f
                val a = Color.alpha(pixel)

                // 三线性插值
                val lutResult = trilinearInterpolation(r, g, b)

                // 修复强度参数处理 - 与processImageDirect保持一致
                val strength = params.strength.coerceIn(0f, 1f)
                val finalR = (r * (1 - strength) + lutResult[0] * strength).coerceIn(0f, 1f)
                val finalG = (g * (1 - strength) + lutResult[1] * strength).coerceIn(0f, 1f)
                val finalB = (b * (1 - strength) + lutResult[2] * strength).coerceIn(0f, 1f)

                blockPixels[i] = Color.argb(
                    a,
                    (finalR * 255).toInt(),
                    (finalG * 255).toInt(),
                    (finalB * 255).toInt()
                )
            }

            // 应用抖动到当前块
            when (params.ditherType) {
                ILutProcessor.DitherType.FLOYD_STEINBERG -> {
                    if (currentY == 0 && currentBlockHeight == height) {
                        applyFloydSteinbergDithering(blockPixels, width, currentBlockHeight)
                    } else {
                        applyRandomDithering(blockPixels)
                    }
                }

                ILutProcessor.DitherType.RANDOM -> applyRandomDithering(blockPixels)
                ILutProcessor.DitherType.NONE -> {}
            }

            // 将处理后的块写回结果bitmap
            resultBitmap.setPixels(blockPixels, 0, width, 0, currentY, width, currentBlockHeight)

            currentY += currentBlockHeight

            // 让出CPU时间，避免阻塞UI
            kotlinx.coroutines.yield()
        }

        return resultBitmap
    }

    private fun trilinearInterpolation(r: Float, g: Float, b: Float): FloatArray {
        val scale = (lutSize - 1).toFloat()
        val rIdx = r * scale
        val gIdx = g * scale
        val bIdx = b * scale

        val r0 = floor(rIdx).toInt().coerceIn(0, lutSize - 1)
        val g0 = floor(gIdx).toInt().coerceIn(0, lutSize - 1)
        val b0 = floor(bIdx).toInt().coerceIn(0, lutSize - 1)

        val r1 = min(r0 + 1, lutSize - 1)
        val g1 = min(g0 + 1, lutSize - 1)
        val b1 = min(b0 + 1, lutSize - 1)

        val rD = rIdx - r0
        val gD = gIdx - g0
        val bD = bIdx - b0

        // 修复：将访问顺序改为与存储顺序一致 [r][g][b]
        val c000 = lut!![r0][g0][b0]
        val c001 = lut!![r1][g0][b0]
        val c010 = lut!![r0][g1][b0]
        val c011 = lut!![r1][g1][b0]
        val c100 = lut!![r0][g0][b1]
        val c101 = lut!![r1][g0][b1]
        val c110 = lut!![r0][g1][b1]
        val c111 = lut!![r1][g1][b1]

        // 三线性插值
        val result = FloatArray(3)
        for (i in 0..2) {
            val c00 = c000[i] * (1 - rD) + c001[i] * rD
            val c01 = c010[i] * (1 - rD) + c011[i] * rD
            val c10 = c100[i] * (1 - rD) + c101[i] * rD
            val c11 = c110[i] * (1 - rD) + c111[i] * rD

            val c0 = c00 * (1 - gD) + c01 * gD
            val c1 = c10 * (1 - gD) + c11 * gD

            result[i] = c0 * (1 - bD) + c1 * bD
        }

        return result
    }

    private fun applyFloydSteinbergDithering(pixels: IntArray, width: Int, height: Int) {
        // 创建浮点数组来存储误差，避免整数截断
        val errors = Array(height) { Array(width) { FloatArray(3) } }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val pixel = pixels[index]

                // 获取当前像素值并加上累积误差
                val r = (Color.red(pixel) / 255f + errors[y][x][0]).coerceIn(0f, 1f)
                val g = (Color.green(pixel) / 255f + errors[y][x][1]).coerceIn(0f, 1f)
                val b = (Color.blue(pixel) / 255f + errors[y][x][2]).coerceIn(0f, 1f)

                // 量化到最接近的整数值
                val newR = round(r * 255f).toInt().coerceIn(0, 255)
                val newG = round(g * 255f).toInt().coerceIn(0, 255)
                val newB = round(b * 255f).toInt().coerceIn(0, 255)

                pixels[index] = Color.argb(Color.alpha(pixel), newR, newG, newB)

                // 计算量化误差
                val errorR = r - newR / 255f
                val errorG = g - newG / 255f
                val errorB = b - newB / 255f

                // 传播误差到相邻像素（Floyd-Steinberg权重）
                if (x < width - 1) {
                    errors[y][x + 1][0] += errorR * 7f / 16f
                    errors[y][x + 1][1] += errorG * 7f / 16f
                    errors[y][x + 1][2] += errorB * 7f / 16f
                }
                if (y < height - 1) {
                    if (x > 0) {
                        errors[y + 1][x - 1][0] += errorR * 3f / 16f
                        errors[y + 1][x - 1][1] += errorG * 3f / 16f
                        errors[y + 1][x - 1][2] += errorB * 3f / 16f
                    }
                    errors[y + 1][x][0] += errorR * 5f / 16f
                    errors[y + 1][x][1] += errorG * 5f / 16f
                    errors[y + 1][x][2] += errorB * 5f / 16f
                    if (x < width - 1) {
                        errors[y + 1][x + 1][0] += errorR * 1f / 16f
                        errors[y + 1][x + 1][1] += errorG * 1f / 16f
                        errors[y + 1][x + 1][2] += errorB * 1f / 16f
                    }
                }
            }
        }
    }

    private fun applyRandomDithering(pixels: IntArray) {
        for (i in pixels.indices) {
            val pixel = pixels[i]
            // 减小噪声强度，使用更小的随机范围
            val noise = (Random.nextFloat() - 0.5f) * 2f // 范围从-1到1

            val r = (Color.red(pixel) + noise).toInt().coerceIn(0, 255)
            val g = (Color.green(pixel) + noise).toInt().coerceIn(0, 255)
            val b = (Color.blue(pixel) + noise).toInt().coerceIn(0, 255)

            pixels[i] = Color.argb(Color.alpha(pixel), r, g, b)
        }
    }

    // 添加设置LUT数据的方法
    internal fun setLutData(lutData: Array<Array<Array<FloatArray>>>?, size: Int) {
        Log.d(
            "CpuLutProcessor",
            "设置LUT数据，尺寸: ${size}x${size}x${size}，数据: ${if (lutData != null) "有效" else "空"}"
        )
        this.lut = lutData
        this.lutSize = size

        // 验证LUT数据完整性
        if (lutData != null) {
            try {
                val expectedSize = size * size * size
                var actualDataPoints = 0

                for (r in 0 until size) {
                    for (g in 0 until size) {
                        for (b in 0 until size) {
                            if (lutData[r][g][b].size == 3) {
                                actualDataPoints++
                            }
                        }
                    }
                }

                Log.d(
                    "CpuLutProcessor",
                    "LUT数据验证完成，期望数据点: $expectedSize，实际数据点: $actualDataPoints"
                )

                if (actualDataPoints != expectedSize) {
                    Log.w("CpuLutProcessor", "LUT数据不完整，可能影响处理效果")
                }
            } catch (e: Exception) {
                Log.e("CpuLutProcessor", "LUT数据验证失败", e)
            }
        }
    }
}
