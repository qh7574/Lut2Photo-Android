package cn.alittlecookie.lut2photo.lut2photo.utils

import android.util.Log
import java.io.File
import java.io.InputStream
import kotlin.math.floor
import kotlin.math.min

/**
 * LUT格式转换工具
 * 支持将不同尺寸的LUT转换为标准33位Cube格式
 */
object LutConverter {
    private const val TAG = "LutConverter"
    private const val TARGET_LUT_SIZE = 33
    
    // 更宽松的正则表达式，支持更多数字格式
    private val DATA_LINE_REGEX =
        Regex("^\\s*([+-]?[0-9]*\\.?[0-9]+(?:[eE][+-]?[0-9]+)?)\\s+([+-]?[0-9]*\\.?[0-9]+(?:[eE][+-]?[0-9]+)?)\\s+([+-]?[0-9]*\\.?[0-9]+(?:[eE][+-]?[0-9]+)?)\\s*$")
    
    /**
     * LUT文件信息
     */
    data class LutInfo(
        val size: Int,
        val format: LutFormat,
        val title: String? = null
    )
    
    /**
     * LUT格式类型
     */
    enum class LutFormat {
        CUBE,  // .cube格式
        VLT    // .vlt格式（松下）
    }
    
    /**
     * 检测LUT文件信息
     */
    fun detectLutInfo(inputStream: InputStream, fileName: String): LutInfo? {
        return try {
            val reader = inputStream.bufferedReader()
            val lines = reader.readLines()
            reader.close()
            
            var lutSize = 0
            var title: String? = null
            
            // 解析头部信息
            for (line in lines) {
                val trimmedLine = line.trim()
                
                // 查找标题
                if (trimmedLine.startsWith("TITLE")) {
                    title = trimmedLine.substringAfter("TITLE").trim().trim('"')
                }
                
                // 查找LUT尺寸
                if (trimmedLine.startsWith("LUT_3D_SIZE")) {
                    val parts = trimmedLine.split("\\s+".toRegex())
                    if (parts.size >= 2) {
                        lutSize = parts[1].toIntOrNull() ?: 0
                        break
                    }
                }
            }
            
            if (lutSize == 0) {
                Log.e(TAG, "未找到有效的LUT_3D_SIZE")
                return null
            }
            
            // 根据文件扩展名判断格式
            val format = when {
                fileName.endsWith(".vlt", ignoreCase = true) -> LutFormat.VLT
                fileName.endsWith(".cube", ignoreCase = true) -> LutFormat.CUBE
                else -> LutFormat.CUBE // 默认为CUBE格式
            }
            
            Log.d(TAG, "检测到LUT信息: 尺寸=$lutSize, 格式=$format, 标题=$title")
            LutInfo(lutSize, format, title)
            
        } catch (e: Exception) {
            Log.e(TAG, "检测LUT信息失败", e)
            null
        }
    }
    
    /**
     * 解析LUT文件为3D数组
     */
    fun parseLutData(inputStream: InputStream): Pair<Array<Array<Array<FloatArray>>>, Int>? {
        return try {
            val reader = inputStream.bufferedReader()
            val lines = reader.readLines()
            reader.close()
            
            var lutSize = 0
            var dataStartIndex = -1
            
            // 解析头部信息
            for ((index, line) in lines.withIndex()) {
                val trimmedLine = line.trim()
                if (trimmedLine.startsWith("LUT_3D_SIZE")) {
                    val parts = trimmedLine.split("\\s+".toRegex())
                    if (parts.size >= 2) {
                        lutSize = parts[1].toInt()
                        dataStartIndex = index + 1
                        break
                    }
                }
            }
            
            if (lutSize == 0) {
                Log.e(TAG, "未找到有效的LUT_3D_SIZE")
                return null
            }
            
            // 初始化LUT数组
            val lutArray = Array(lutSize) { Array(lutSize) { Array(lutSize) { FloatArray(3) } } }
            
            // 解析数据行
            var dataIndex = 0
            val expectedSize = lutSize * lutSize * lutSize
            
            for (i in dataStartIndex until lines.size) {
                if (dataIndex >= expectedSize) break
                
                val line = lines[i].trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("TITLE") || 
                    line.startsWith("DOMAIN_")) {
                    continue
                }
                
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
                        Log.w(TAG, "无法解析数据行 ${i + 1}: $line", e)
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
                        } catch (_: NumberFormatException) {
                            // 忽略无法解析的行
                        }
                    }
                }
            }
            
            if (dataIndex == expectedSize) {
                Log.d(TAG, "LUT数据解析成功，尺寸: $lutSize, 数据点: $dataIndex")
                
                // 检测数据范围并归一化
                var minVal = Float.MAX_VALUE
                var maxVal = Float.MIN_VALUE
                for (r in 0 until lutSize) {
                    for (g in 0 until lutSize) {
                        for (b in 0 until lutSize) {
                            for (c in 0..2) {
                                val value = lutArray[r][g][b][c]
                                minVal = kotlin.math.min(minVal, value)
                                maxVal = kotlin.math.max(maxVal, value)
                            }
                        }
                    }
                }
                
                Log.d(TAG, "原始LUT数据范围: min=$minVal, max=$maxVal")
                
                // 如果数据范围不是标准的[0, 1]，进行归一化
                // 常见范围：[0, 1], [0, 255], [0, 1023], [0, 4095]
                if (maxVal > 1.1f) {
                    Log.w(TAG, "检测到非标准数据范围，进行归一化: [$minVal, $maxVal] -> [0, 1]")
                    
                    // 自动检测可能的最大值
                    val detectedMax = when {
                        maxVal <= 1.0f -> 1.0f
                        maxVal <= 255.0f -> 255.0f
                        maxVal <= 1023.0f -> 1023.0f
                        maxVal <= 4095.0f -> 4095.0f
                        maxVal <= 65535.0f -> 65535.0f
                        else -> maxVal
                    }
                    
                    Log.d(TAG, "检测到的数据格式最大值: $detectedMax")
                    
                    // 归一化到[0, 1]
                    for (r in 0 until lutSize) {
                        for (g in 0 until lutSize) {
                            for (b in 0 until lutSize) {
                                for (c in 0..2) {
                                    lutArray[r][g][b][c] = lutArray[r][g][b][c] / detectedMax
                                }
                            }
                        }
                    }
                    
                    Log.d(TAG, "归一化完成，新范围: [${minVal/detectedMax}, ${maxVal/detectedMax}]")
                }
                
                Pair(lutArray, lutSize)
            } else {
                Log.e(TAG, "LUT数据不完整: 期望 $expectedSize，实际 $dataIndex")
                null
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "解析LUT数据失败", e)
            null
        }
    }
    
    /**
     * 将任意尺寸的LUT转换为33位LUT
     * 使用三线性插值算法
     */
    fun convertTo33Cube(
        sourceLut: Array<Array<Array<FloatArray>>>,
        sourceSize: Int,
        title: String? = null
    ): String {
        Log.d(TAG, "开始转换LUT: ${sourceSize}x${sourceSize}x${sourceSize} -> ${TARGET_LUT_SIZE}x${TARGET_LUT_SIZE}x${TARGET_LUT_SIZE}")
        
        // 验证源LUT数据范围
        var minVal = Float.MAX_VALUE
        var maxVal = Float.MIN_VALUE
        var sampleCount = 0
        for (r in 0 until sourceSize) {
            for (g in 0 until sourceSize) {
                for (b in 0 until sourceSize) {
                    for (c in 0..2) {
                        val value = sourceLut[r][g][b][c]
                        minVal = kotlin.math.min(minVal, value)
                        maxVal = kotlin.math.max(maxVal, value)
                        if (sampleCount < 10) {
                            Log.v(TAG, "源LUT[$r][$g][$b][$c] = $value")
                            sampleCount++
                        }
                    }
                }
            }
        }
        Log.d(TAG, "源LUT数据范围: min=$minVal, max=$maxVal")
        
        val builder = StringBuilder()
        
        // 写入头部
        builder.appendLine("# Converted from ${sourceSize}x${sourceSize}x${sourceSize} to ${TARGET_LUT_SIZE}x${TARGET_LUT_SIZE}x${TARGET_LUT_SIZE}")
        if (title != null) {
            builder.appendLine("TITLE \"$title\"")
        }
        builder.appendLine("LUT_3D_SIZE $TARGET_LUT_SIZE")
        builder.appendLine("DOMAIN_MIN 0.0 0.0 0.0")
        builder.appendLine("DOMAIN_MAX 1.0 1.0 1.0")
        builder.appendLine()
        
        // 生成33x33x33的数据
        // 标准Cube格式的数据顺序：B最外层，G中层，R最内层
        // 这样dataIndex的计算方式为：rIndex = dataIndex % size, gIndex = (dataIndex / size) % size, bIndex = dataIndex / (size * size)
        val scale = (TARGET_LUT_SIZE - 1).toFloat()
        
        for (b in 0 until TARGET_LUT_SIZE) {
            for (g in 0 until TARGET_LUT_SIZE) {
                for (r in 0 until TARGET_LUT_SIZE) {
                    // 计算归一化的RGB值 (0.0 - 1.0)
                    val rNorm = r / scale
                    val gNorm = g / scale
                    val bNorm = b / scale
                    
                    // 使用三线性插值从源LUT中查找对应的颜色
                    val color = trilinearInterpolation(rNorm, gNorm, bNorm, sourceLut, sourceSize)
                    
                    // 写入数据行
                    builder.appendLine("${color[0]} ${color[1]} ${color[2]}")
                }
            }
        }
        
        Log.d(TAG, "LUT转换完成")
        return builder.toString()
    }
    
    /**
     * 三线性插值算法
     * 从源LUT中插值出目标颜色值
     */
    private fun trilinearInterpolation(
        r: Float,
        g: Float,
        b: Float,
        lut: Array<Array<Array<FloatArray>>>,
        lutSize: Int
    ): FloatArray {
        // 确保输入值在[0, 1]范围内
        val rClamped = r.coerceIn(0f, 1f)
        val gClamped = g.coerceIn(0f, 1f)
        val bClamped = b.coerceIn(0f, 1f)
        
        val scale = (lutSize - 1).toFloat()
        val rIdx = rClamped * scale
        val gIdx = gClamped * scale
        val bIdx = bClamped * scale
        
        val r0 = floor(rIdx).toInt().coerceIn(0, lutSize - 1)
        val g0 = floor(gIdx).toInt().coerceIn(0, lutSize - 1)
        val b0 = floor(bIdx).toInt().coerceIn(0, lutSize - 1)
        
        val r1 = min(r0 + 1, lutSize - 1)
        val g1 = min(g0 + 1, lutSize - 1)
        val b1 = min(b0 + 1, lutSize - 1)
        
        val rD = rIdx - r0
        val gD = gIdx - g0
        val bD = bIdx - b0
        
        // 获取立方体8个顶点的颜色值
        val c000 = lut[r0][g0][b0]
        val c001 = lut[r1][g0][b0]
        val c010 = lut[r0][g1][b0]
        val c011 = lut[r1][g1][b0]
        val c100 = lut[r0][g0][b1]
        val c101 = lut[r1][g0][b1]
        val c110 = lut[r0][g1][b1]
        val c111 = lut[r1][g1][b1]
        
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
        
        // 确保输出值在合理范围内（允许稍微超出[0,1]，但不能太离谱）
        for (i in 0..2) {
            if (result[i] < -0.1f || result[i] > 1.1f) {
                Log.w(TAG, "插值结果超出正常范围: result[$i]=${result[i]}, 输入RGB=($r,$g,$b)")
            }
            // 不强制限制在[0,1]，因为某些LUT可能有超出范围的值
        }
        
        return result
    }
    
    /**
     * 将LUT数据写入文件
     */
    fun writeCubeFile(cubeContent: String, outputFile: File): Boolean {
        return try {
            outputFile.writeText(cubeContent)
            Log.d(TAG, "LUT文件写入成功: ${outputFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "LUT文件写入失败", e)
            false
        }
    }
    
    /**
     * 验证LUT文件格式
     */
    fun validateLutFile(file: File): Boolean {
        return try {
            file.readLines().any { line ->
                line.trim().startsWith("LUT_3D_SIZE")
            }
        } catch (_: Exception) {
            false
        }
    }
}
