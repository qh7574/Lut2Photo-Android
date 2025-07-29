package cn.alittlecookie.lut2photo.lut2photo.core

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import kotlin.math.*
import kotlin.random.Random

class LutProcessor {
    
    data class ProcessingParams(
        val strength: Int = 100,
        val quality: Int = 90,
        val ditherType: DitherType = DitherType.NONE
    )
    
    enum class DitherType {
        NONE,
        FLOYD_STEINBERG,
        RANDOM
    }
    
    private var lut: Array<Array<Array<FloatArray>>>? = null
    private var lutSize: Int = 0
    
    fun loadCubeLut(inputStream: InputStream): Boolean {
        return try {
            android.util.Log.d("LutProcessor", "开始加载LUT文件")
            val lines = inputStream.bufferedReader().readLines()
            android.util.Log.d("LutProcessor", "读取到 ${lines.size} 行数据")
            
            var currentLine = 0
            
            // 跳过注释、空行和TITLE行，寻找LUT_3D_SIZE
            while (currentLine < lines.size) {
                val line = lines[currentLine].trim()
                android.util.Log.d("LutProcessor", "检查第 ${currentLine + 1} 行: $line")
                
                if (line.startsWith("LUT_3D_SIZE")) {
                    android.util.Log.d("LutProcessor", "找到LUT_3D_SIZE行: $line")
                    break
                }
                
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("TITLE") || 
                    line.startsWith("DOMAIN_MIN") || line.startsWith("DOMAIN_MAX")) {
                    android.util.Log.d("LutProcessor", "跳过第 ${currentLine + 1} 行: $line")
                    currentLine++
                    continue
                }
                
                currentLine++
            }
            
            if (currentLine >= lines.size) {
                android.util.Log.e("LutProcessor", "没有找到有效的LUT数据行")
                return false
            }
            
            // 读取LUT_3D_SIZE
            val sizeLine = lines[currentLine]
            android.util.Log.d("LutProcessor", "解析LUT_3D_SIZE行: $sizeLine")
            
            if (!sizeLine.startsWith("LUT_3D_SIZE")) {
                android.util.Log.e("LutProcessor", "未找到LUT_3D_SIZE标识，当前行: $sizeLine")
                return false
            }
            
            val sizeLineParts = sizeLine.split(" ")
            if (sizeLineParts.size < 2) {
                android.util.Log.e("LutProcessor", "LUT_3D_SIZE行格式错误: $sizeLine")
                return false
            }
            
            try {
                lutSize = sizeLineParts[1].toInt()
                android.util.Log.d("LutProcessor", "LUT尺寸: $lutSize")
            } catch (e: NumberFormatException) {
                android.util.Log.e("LutProcessor", "无法解析LUT尺寸: ${sizeLineParts[1]}", e)
                return false
            }
            
            currentLine++
            
            // 初始化LUT数组
            lut = Array(lutSize) { Array(lutSize) { Array(lutSize) { FloatArray(3) } } }
            android.util.Log.d("LutProcessor", "LUT数组初始化完成")
            
            // 读取LUT数据
            var b = 0
            var g = 0
            var r = 0
            var dataLineCount = 0
            
            while (currentLine < lines.size && b < lutSize) {
                val line = lines[currentLine].trim()
                if (line.isEmpty() || line.startsWith("#")) {
                    currentLine++
                    continue
                }
                
                // 添加更多CUBE文件格式关键字过滤
                if (line.startsWith("DOMAIN_MIN") || line.startsWith("DOMAIN_MAX") ||
                    line.startsWith("TITLE") || line.startsWith("LUT_1D_SIZE") ||
                    line.startsWith("LUT_1D_INPUT_RANGE") || line.startsWith("LUT_3D_INPUT_RANGE") ||
                    line.startsWith("CHANNELS") || line.startsWith("LUT_1D_OUTPUT_RANGE") ||
                    line.startsWith("LUT_3D_OUTPUT_RANGE")) {
                    android.util.Log.d("LutProcessor", "跳过CUBE格式关键字行: $line")
                    currentLine++
                    continue
                }
                
                val values = line.split("\\s+".toRegex())
                if (values.size >= 3) {
                    try {
                        // 验证是否为有效的数字数据
                        val r1 = values[0].toFloat()
                        val g1 = values[1].toFloat()
                        val b1 = values[2].toFloat()
                        
                        lut!![b][g][r][0] = r1
                        lut!![b][g][r][1] = g1
                        lut!![b][g][r][2] = b1
                        
                        dataLineCount++
                        if (dataLineCount <= 5) {
                            android.util.Log.d("LutProcessor", "数据行 $dataLineCount: [${values[0]}, ${values[1]}, ${values[2]}] -> [$b][$g][$r]")
                        }
                        
                        r++
                        if (r >= lutSize) {
                            r = 0
                            g++
                            if (g >= lutSize) {
                                g = 0
                                b++
                            }
                        }
                    } catch (e: NumberFormatException) {
                        android.util.Log.w("LutProcessor", "跳过无法解析的数据行: $line")
                    }
                } else {
                    android.util.Log.w("LutProcessor", "数据行格式不正确: $line (值数量: ${values.size})")
                }
                currentLine++
            }
            
            val expectedDataPoints = lutSize * lutSize * lutSize
            android.util.Log.d("LutProcessor", "LUT数据加载完成，期望数据点: $expectedDataPoints，实际数据点: $dataLineCount")
            
            if (dataLineCount < expectedDataPoints) {
                android.util.Log.w("LutProcessor", "LUT数据不完整，但继续使用")
            }
            
            android.util.Log.d("LutProcessor", "LUT文件加载成功")
            true
        } catch (e: Exception) {
            android.util.Log.e("LutProcessor", "LUT文件加载异常", e)
            e.printStackTrace()
            false
        }
    }
    
    suspend fun processImage(bitmap: Bitmap, params: ProcessingParams): Bitmap? = withContext(Dispatchers.Default) {
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
    
    private suspend fun processImageDirect(bitmap: Bitmap, params: ProcessingParams): Bitmap? {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        // 应用LUT处理
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = Color.red(pixel) / 255f
            val g = Color.green(pixel) / 255f
            val b = Color.blue(pixel) / 255f
            val a = Color.alpha(pixel)
            
            // 三线性插值
            val lutResult = trilinearInterpolation(r, g, b)
            
            // 应用强度
            val strength = params.strength / 100f
            val finalR = (r * (1 - strength) + lutResult[0] * strength).coerceIn(0f, 1f)
            val finalG = (g * (1 - strength) + lutResult[1] * strength).coerceIn(0f, 1f)
            val finalB = (b * (1 - strength) + lutResult[2] * strength).coerceIn(0f, 1f)
            
            pixels[i] = Color.argb(a, (finalR * 255).toInt(), (finalG * 255).toInt(), (finalB * 255).toInt())
        }
        
        // 应用抖动
        when (params.ditherType) {
            DitherType.FLOYD_STEINBERG -> applyFloydSteinbergDithering(pixels, width, height)
            DitherType.RANDOM -> applyRandomDithering(pixels)
            DitherType.NONE -> {}
        }
        
        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        resultBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return resultBitmap
    }
    
    private suspend fun processImageInBlocks(bitmap: Bitmap, params: ProcessingParams, maxBlockSize: Int): Bitmap? {
        val width = bitmap.width
        val height = bitmap.height
        
        // 计算合适的块高度
        val blockHeight = maxBlockSize / width
        val actualBlockHeight = minOf(blockHeight, height)
        
        // 创建结果bitmap
        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        
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
                
                // 应用强度
                val strength = params.strength / 100f
                val finalR = (r * (1 - strength) + lutResult[0] * strength).coerceIn(0f, 1f)
                val finalG = (g * (1 - strength) + lutResult[1] * strength).coerceIn(0f, 1f)
                val finalB = (b * (1 - strength) + lutResult[2] * strength).coerceIn(0f, 1f)
                
                blockPixels[i] = Color.argb(a, (finalR * 255).toInt(), (finalG * 255).toInt(), (finalB * 255).toInt())
            }
            
            // 应用抖动到当前块（注意：Floyd-Steinberg抖动在分块处理时可能会有边界效应）
            when (params.ditherType) {
                DitherType.FLOYD_STEINBERG -> {
                    // 对于Floyd-Steinberg，我们需要特殊处理以避免块边界问题
                    if (currentY == 0 && currentBlockHeight == height) {
                        // 如果是单块处理整个图片
                        applyFloydSteinbergDithering(blockPixels, width, currentBlockHeight)
                    } else {
                        // 分块时使用随机抖动替代，避免边界问题
                        applyRandomDithering(blockPixels)
                    }
                }
                DitherType.RANDOM -> applyRandomDithering(blockPixels)
                DitherType.NONE -> {}
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
        
        // 获取8个角点的值
        val c000 = lut!![b0][g0][r0]
        val c001 = lut!![b0][g0][r1]
        val c010 = lut!![b0][g1][r0]
        val c011 = lut!![b0][g1][r1]
        val c100 = lut!![b1][g0][r0]
        val c101 = lut!![b1][g0][r1]
        val c110 = lut!![b1][g1][r0]
        val c111 = lut!![b1][g1][r1]
        
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
                val newR = kotlin.math.round(r * 255f).toInt().coerceIn(0, 255)
                val newG = kotlin.math.round(g * 255f).toInt().coerceIn(0, 255)
                val newB = kotlin.math.round(b * 255f).toInt().coerceIn(0, 255)
                
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

    // 添加新的混合方法，参考Python版本的非线性混合
    private fun applyLutWithStrength(originalPixels: IntArray, lutPixels: IntArray, strength: Float) {
        val normalizedStrength = strength.coerceIn(0f, 1f)
        
        for (i in originalPixels.indices) {
            val original = originalPixels[i]
            val lut = lutPixels[i]
            
            val origR = Color.red(original) / 255f
            val origG = Color.green(original) / 255f
            val origB = Color.blue(original) / 255f
            
            val lutR = Color.red(lut) / 255f
            val lutG = Color.green(lut) / 255f
            val lutB = Color.blue(lut) / 255f
            
            // 使用非线性混合减少色彩断层
            val blendedR = kotlin.math.sqrt(
                (1 - normalizedStrength) * origR * origR + 
                normalizedStrength * lutR * lutR
            )
            val blendedG = kotlin.math.sqrt(
                (1 - normalizedStrength) * origG * origG + 
                normalizedStrength * lutG * lutG
            )
            val blendedB = kotlin.math.sqrt(
                (1 - normalizedStrength) * origB * origB + 
                normalizedStrength * lutB * lutB
            )
            
            val finalR = (blendedR * 255).toInt().coerceIn(0, 255)
            val finalG = (blendedG * 255).toInt().coerceIn(0, 255)
            val finalB = (blendedB * 255).toInt().coerceIn(0, 255)
            
            originalPixels[i] = Color.argb(Color.alpha(original), finalR, finalG, finalB)
        }
    }
}