package cn.alittlecookie.lut2photo.lut2photo.core

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.core.graphics.createBitmap
import cn.alittlecookie.lut2photo.lut2photo.model.FilmGrainConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.math.ln
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * CPU胶片颗粒处理器
 * 采用简化高斯噪声 + 亮度调制方案
 * 支持分块处理大图片，与CpuLutProcessor分块策略一致
 */
class FilmGrainProcessor {
    
    companion object {
        private const val TAG = "FilmGrainProcessor"
        // 与CpuLutProcessor保持一致的分块阈值
        private const val MAX_BLOCK_PIXELS = 16 * 1024 * 1024  // 1600万像素
        // 噪声强度经验系数
        private const val NOISE_INTENSITY_FACTOR = 25f
    }
    
    /**
     * 处理图像，添加胶片颗粒效果
     * @param bitmap 输入图像
     * @param config 颗粒配置
     * @return 处理后的图像，失败返回null
     */
    suspend fun processImage(bitmap: Bitmap, config: FilmGrainConfig): Bitmap? {
        if (!config.isEnabled || config.globalStrength <= 0f) {
            Log.d(TAG, "颗粒效果未启用或强度为0，返回原图")
            return bitmap
        }
        
        return withContext(Dispatchers.Default) {
            try {
                val width = bitmap.width
                val height = bitmap.height
                val totalPixels = width * height
                
                Log.d(TAG, "开始处理颗粒效果，图片尺寸: ${width}x${height}, 总像素: $totalPixels")
                
                if (totalPixels <= MAX_BLOCK_PIXELS) {
                    processImageDirect(bitmap, config)
                } else {
                    processImageInBlocks(bitmap, config)
                }
            } catch (e: Exception) {
                Log.e(TAG, "颗粒处理失败", e)
                null
            }
        }
    }

    
    /**
     * 直接处理（小图片）
     */
    private fun processImageDirect(bitmap: Bitmap, config: FilmGrainConfig): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        // 生成基础噪声种子（用于通道相关性）
        val baseNoiseSeed = Random.nextLong()
        
        applyGrainToPixels(pixels, width, height, config, baseNoiseSeed)
        
        val resultBitmap = createBitmap(width, height)
        resultBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        
        Log.d(TAG, "直接处理完成")
        return resultBitmap
    }
    
    /**
     * 分块处理（大图片）
     * 与CpuLutProcessor.processImageInBlocks保持一致的分块策略
     */
    private suspend fun processImageInBlocks(bitmap: Bitmap, config: FilmGrainConfig): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        // 计算块高度，与CpuLutProcessor一致
        val blockHeight = MAX_BLOCK_PIXELS / width
        val actualBlockHeight = minOf(blockHeight, height)
        
        val resultBitmap = createBitmap(width, height)
        val baseNoiseSeed = Random.nextLong()
        
        var currentY = 0
        var blockIndex = 0
        
        Log.d(TAG, "开始分块处理，块高度: $actualBlockHeight")
        
        while (currentY < height) {
            val remainingHeight = height - currentY
            val currentBlockHeight = minOf(actualBlockHeight, remainingHeight)
            
            val blockPixels = IntArray(width * currentBlockHeight)
            bitmap.getPixels(blockPixels, 0, width, 0, currentY, width, currentBlockHeight)
            
            // 为每个块生成不同但相关的噪声种子
            val blockSeed = baseNoiseSeed + currentY
            applyGrainToPixels(blockPixels, width, currentBlockHeight, config, blockSeed)
            
            resultBitmap.setPixels(blockPixels, 0, width, 0, currentY, width, currentBlockHeight)
            
            currentY += currentBlockHeight
            blockIndex++
            
            yield() // 让出CPU时间
            
            Log.d(TAG, "块 $blockIndex 处理完成，进度: $currentY/$height")
        }
        
        Log.d(TAG, "分块处理完成，共处理 $blockIndex 个块")
        return resultBitmap
    }
    
    /**
     * 对像素数组应用颗粒效果
     * 采用简化高斯噪声 + 亮度调制
     */
    private fun applyGrainToPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        config: FilmGrainConfig,
        baseSeed: Long
    ) {
        val random = Random(baseSeed)
        
        // 预生成通道相关噪声
        val correlatedNoise = FloatArray(pixels.size)
        for (i in correlatedNoise.indices) {
            correlatedNoise[i] = gaussianNoise(random)
        }
        
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            val a = Color.alpha(pixel)
            
            // 计算亮度（使用标准系数）
            val luminance = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            
            // 获取当前亮度下的颗粒强度和尺寸
            val strength = config.getActualStrength(luminance)
            val sizeRatio = config.getGrainSizeRatio(luminance)
            
            // 基础噪声强度（考虑颗粒大小的影响）
            val baseNoiseStrength = strength * config.grainSize * sizeRatio * NOISE_INTENSITY_FACTOR
            
            // 生成各通道噪声（考虑通道相关性）
            val correlation = config.channelCorrelation
            val baseNoise = correlatedNoise[i]
            
            val rNoise = (baseNoise * correlation + gaussianNoise(random) * (1 - correlation)) * 
                         config.redChannelRatio * baseNoiseStrength
            val gNoise = (baseNoise * correlation + gaussianNoise(random) * (1 - correlation)) * 
                         config.greenChannelRatio * baseNoiseStrength
            val bNoise = (baseNoise * correlation + gaussianNoise(random) * (1 - correlation)) * 
                         config.blueChannelRatio * baseNoiseStrength
            
            // 应用颗粒并保护色彩
            val preservation = config.colorPreservation
            val newR = (r + rNoise * preservation).toInt().coerceIn(0, 255)
            val newG = (g + gNoise * preservation).toInt().coerceIn(0, 255)
            val newB = (b + bNoise * preservation).toInt().coerceIn(0, 255)
            
            pixels[i] = Color.argb(a, newR, newG, newB)
        }
    }
    
    /**
     * Box-Muller变换生成高斯噪声
     */
    private fun gaussianNoise(random: Random): Float {
        val u1 = random.nextFloat().coerceAtLeast(0.0001f)
        val u2 = random.nextFloat()
        return (sqrt(-2.0 * ln(u1.toDouble())) * cos(2.0 * Math.PI * u2)).toFloat()
    }
    
    /**
     * 同步版本的颗粒处理方法（用于非协程环境）
     * @param bitmap 输入图像
     * @param config 颗粒配置
     * @return 处理后的图像，失败返回null
     */
    fun applyGrain(bitmap: Bitmap, config: FilmGrainConfig): Bitmap? {
        if (!config.isEnabled || config.globalStrength <= 0f) {
            Log.d(TAG, "颗粒效果未启用或强度为0，返回原图")
            return bitmap
        }
        
        return try {
            val width = bitmap.width
            val height = bitmap.height
            width * height
            
            Log.d(TAG, "开始处理颗粒效果（同步），图片尺寸: ${width}x${height}")
            
            // 同步版本只使用直接处理，避免协程
            processImageDirect(bitmap, config)
        } catch (e: Exception) {
            Log.e(TAG, "颗粒处理失败", e)
            null
        }
    }
}
