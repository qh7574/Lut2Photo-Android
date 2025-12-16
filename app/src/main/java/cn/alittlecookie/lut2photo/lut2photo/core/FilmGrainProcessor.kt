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
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * CPU胶片颗粒处理器
 * 完全复刻GPU着色器算法，确保CPU和GPU输出效果一致
 * 使用多重哈希随机数 + Box-Muller高斯变换，无空间插值
 */
class FilmGrainProcessor {
    
    companion object {
        private const val TAG = "FilmGrainProcessor"
        // 与CpuLutProcessor保持一致的分块阈值
        private const val MAX_BLOCK_PIXELS = 16 * 1024 * 1024  // 1600万像素
        // 噪声强度系数（与GPU着色器一致：0.1）
        private const val NOISE_INTENSITY_FACTOR = 0.1f
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
                
                // 生成随机种子（与GPU的u_grainSeed对应）
                val grainSeed = Random.nextFloat() * 1000f
                
                if (totalPixels <= MAX_BLOCK_PIXELS) {
                    processImageDirect(bitmap, config, grainSeed)
                } else {
                    processImageInBlocks(bitmap, config, grainSeed)
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
    private fun processImageDirect(bitmap: Bitmap, config: FilmGrainConfig, grainSeed: Float): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        applyGrainToPixels(pixels, width, height, config, grainSeed, width, height, 0)
        
        val resultBitmap = createBitmap(width, height)
        resultBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        
        Log.d(TAG, "直接处理完成")
        return resultBitmap
    }
    
    /**
     * 分块处理（大图片）
     * 基于全局坐标计算噪声，无需边界混合
     */
    private suspend fun processImageInBlocks(bitmap: Bitmap, config: FilmGrainConfig, grainSeed: Float): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        // 计算块高度
        val blockHeight = MAX_BLOCK_PIXELS / width
        val actualBlockHeight = minOf(blockHeight, height)
        
        val resultBitmap = createBitmap(width, height)
        
        var currentY = 0
        var blockIndex = 0
        
        Log.d(TAG, "开始分块处理，块高度: $actualBlockHeight")
        
        while (currentY < height) {
            val remainingHeight = height - currentY
            val currentBlockHeight = minOf(actualBlockHeight, remainingHeight)
            
            val blockPixels = IntArray(width * currentBlockHeight)
            bitmap.getPixels(blockPixels, 0, width, 0, currentY, width, currentBlockHeight)
            
            // 使用全局坐标处理，噪声天然连续，无需边界混合
            applyGrainToPixels(blockPixels, width, currentBlockHeight, config, grainSeed, width, height, currentY)
            
            resultBitmap.setPixels(blockPixels, 0, width, 0, currentY, width, currentBlockHeight)
            
            currentY += currentBlockHeight
            blockIndex++
            
            yield() // 让出CPU时间
            
            Log.d(TAG, "块 $blockIndex 处理完成，进度: $currentY/$height")
        }
        
        Log.d(TAG, "分块处理完成，共处理 $blockIndex 个块")
        return resultBitmap
    }

    
    // ==================== GPU算法复刻：随机数生成 ====================
    
    /**
     * 改进的随机数生成（多重哈希，与GPU着色器randomImproved一致）
     * GPU代码：
     * float randomImproved(vec2 co, float seed) {
     *     vec2 p = co + seed;
     *     float h1 = fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
     *     float h2 = fract(sin(dot(p, vec2(269.5, 183.3))) * 43758.5453123);
     *     return fract(h1 + h2);
     * }
     */
    private fun randomImproved(x: Float, y: Float, seed: Float): Float {
        val px = x + seed
        val py = y + seed
        
        // 第一层哈希
        val dot1 = px * 127.1f + py * 311.7f
        val h1 = fract(sin(dot1) * 43758.5453123f)
        
        // 第二层哈希
        val dot2 = px * 269.5f + py * 183.3f
        val h2 = fract(sin(dot2) * 43758.5453123f)
        
        // 组合两层哈希
        return fract(h1 + h2)
    }
    
    /**
     * 取小数部分（与GLSL fract一致）
     */
    private fun fract(x: Float): Float {
        return x - kotlin.math.floor(x)
    }
    
    /**
     * 高斯噪声生成（Box-Muller变换，与GPU着色器gaussianNoise一致）
     * GPU代码：
     * float gaussianNoise(vec2 uv, float seed) {
     *     float u1 = max(randomImproved(uv, seed), 0.0001);
     *     float u2 = randomImproved(uv, seed + 0.5);
     *     return sqrt(-2.0 * log(u1)) * cos(6.28318 * u2);
     * }
     */
    private fun gaussianNoise(x: Float, y: Float, seed: Float): Float {
        val u1 = randomImproved(x, y, seed).coerceAtLeast(0.0001f)
        val u2 = randomImproved(x, y, seed + 0.5f)
        return (sqrt(-2.0 * ln(u1.toDouble())) * cos(6.28318 * u2)).toFloat()
    }
    
    // ==================== GPU算法复刻：亮度相关参数 ====================
    
    /**
     * 平滑插值函数（与GPU着色器smoothstep3一致）
     */
    private fun smoothstep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }
    
    /**
     * 线性插值（与GLSL mix一致）
     */
    private fun mix(a: Float, b: Float, t: Float): Float {
        return a * (1f - t) + b * t
    }
    
    /**
     * 根据亮度获取颗粒强度比例（与GPU着色器getGrainStrengthRatio一致）
     */
    private fun getGrainStrengthRatio(luminance: Float, config: FilmGrainConfig): Float {
        val shadowThreshold = config.shadowThreshold / 255f
        val highlightThreshold = config.highlightThreshold / 255f
        val transitionWidth = 0.04f
        
        return when {
            luminance < shadowThreshold - transitionWidth -> config.shadowGrainRatio
            luminance < shadowThreshold + transitionWidth -> {
                val t = smoothstep((luminance - (shadowThreshold - transitionWidth)) / (2f * transitionWidth))
                mix(config.shadowGrainRatio, config.midtoneGrainRatio, t)
            }
            luminance < highlightThreshold - transitionWidth -> config.midtoneGrainRatio
            luminance < highlightThreshold + transitionWidth -> {
                val t = smoothstep((luminance - (highlightThreshold - transitionWidth)) / (2f * transitionWidth))
                mix(config.midtoneGrainRatio, config.highlightGrainRatio, t)
            }
            else -> config.highlightGrainRatio
        }
    }
    
    /**
     * 根据亮度获取颗粒尺寸比例（与GPU着色器getGrainSizeRatio一致）
     */
    private fun getGrainSizeRatio(luminance: Float, config: FilmGrainConfig): Float {
        val shadowThreshold = config.shadowThreshold / 255f
        val highlightThreshold = config.highlightThreshold / 255f
        val transitionWidth = 0.04f
        
        return when {
            luminance < shadowThreshold - transitionWidth -> config.shadowSizeRatio
            luminance < shadowThreshold + transitionWidth -> {
                val t = smoothstep((luminance - (shadowThreshold - transitionWidth)) / (2f * transitionWidth))
                mix(config.shadowSizeRatio, 1f, t)
            }
            luminance < highlightThreshold - transitionWidth -> 1f
            luminance < highlightThreshold + transitionWidth -> {
                val t = smoothstep((luminance - (highlightThreshold - transitionWidth)) / (2f * transitionWidth))
                mix(1f, config.highlightSizeRatio, t)
            }
            else -> config.highlightSizeRatio
        }
    }

    
    // ==================== 核心处理：完全复刻GPU applyFilmGrain ====================
    
    /**
     * 对像素数组应用颗粒效果
     * 完全复刻GPU着色器的applyFilmGrain函数
     * 
     * @param pixels 像素数组
     * @param blockWidth 当前块宽度
     * @param blockHeight 当前块高度
     * @param config 颗粒配置
     * @param grainSeed 随机种子（对应GPU的u_grainSeed）
     * @param totalWidth 图片总宽度（用于计算normalizedFreq）
     * @param totalHeight 图片总高度（用于计算normalizedFreq）
     * @param offsetY 当前块在整图中的Y偏移
     */
    private fun applyGrainToPixels(
        pixels: IntArray,
        blockWidth: Int,
        blockHeight: Int,
        config: FilmGrainConfig,
        grainSeed: Float,
        totalWidth: Int,
        totalHeight: Int,
        offsetY: Int
    ) {
        // 计算归一化频率（与GPU着色器一致）
        // GPU: float avgTexSize = (texSize.x + texSize.y) * 0.5;
        // GPU: float normalizedFreq = avgTexSize / 1000.0;
        val avgTexSize = (totalWidth + totalHeight) * 0.5f
        val normalizedFreq = avgTexSize / 1000f
        
        for (y in 0 until blockHeight) {
            for (x in 0 until blockWidth) {
                val i = y * blockWidth + x
                val pixel = pixels[i]
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val a = Color.alpha(pixel)
                
                // 计算亮度（与GPU着色器一致：dot(color, vec3(0.299, 0.587, 0.114))）
                val luminance = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                
                // 获取当前亮度下的颗粒参数
                val strengthRatio = getGrainStrengthRatio(luminance, config)
                val sizeRatio = getGrainSizeRatio(luminance, config)
                
                // 计算实际噪声强度（与GPU着色器一致）
                // GPU: float noiseStrength = u_grainStrength * strengthRatio * u_grainSize * sizeRatio * 0.1;
                val noiseStrength = config.globalStrength * strengthRatio * config.grainSize * sizeRatio * NOISE_INTENSITY_FACTOR
                
                // 如果噪声强度为0，跳过处理
                if (noiseStrength <= 0f) continue
                
                // 全局像素坐标（用于跨块一致性）
                val globalY = offsetY + y
                
                // 计算噪声UV坐标（与GPU着色器一致）
                // GPU: vec2 pixelCoord = uv * texSize;
                // GPU: vec2 grainUV = pixelCoord / (u_grainSize * sizeRatio * normalizedFreq);
                val grainUVx = x.toFloat() / (config.grainSize * sizeRatio * normalizedFreq)
                val grainUVy = globalY.toFloat() / (config.grainSize * sizeRatio * normalizedFreq)
                
                // 生成基础噪声（与GPU着色器一致）
                // GPU: float baseNoise = gaussianNoise(grainUV, u_grainSeed);
                val baseNoise = gaussianNoise(grainUVx, grainUVy, grainSeed)
                
                // 生成各通道噪声（与GPU着色器一致）
                // GPU: float rNoise = mix(gaussianNoise(grainUV, u_grainSeed + 0.1), baseNoise, u_channelCorrelation) * u_redChannelRatio;
                val rIndependent = gaussianNoise(grainUVx, grainUVy, grainSeed + 0.1f)
                val rNoise = mix(rIndependent, baseNoise, config.channelCorrelation) * config.redChannelRatio
                
                val gIndependent = gaussianNoise(grainUVx, grainUVy, grainSeed + 0.2f)
                val gNoise = mix(gIndependent, baseNoise, config.channelCorrelation) * config.greenChannelRatio
                
                val bIndependent = gaussianNoise(grainUVx, grainUVy, grainSeed + 0.3f)
                val bNoise = mix(bIndependent, baseNoise, config.channelCorrelation) * config.blueChannelRatio
                
                // 应用颗粒并保护色彩（与GPU着色器一致）
                // GPU: vec3 noise = vec3(rNoise, gNoise, bNoise) * noiseStrength * u_colorPreservation;
                // GPU: return color + noise;
                val finalNoiseR = rNoise * noiseStrength * config.colorPreservation * 255f
                val finalNoiseG = gNoise * noiseStrength * config.colorPreservation * 255f
                val finalNoiseB = bNoise * noiseStrength * config.colorPreservation * 255f
                
                val newR = (r + finalNoiseR).toInt().coerceIn(0, 255)
                val newG = (g + finalNoiseG).toInt().coerceIn(0, 255)
                val newB = (b + finalNoiseB).toInt().coerceIn(0, 255)
                
                pixels[i] = Color.argb(a, newR, newG, newB)
            }
        }
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
            
            Log.d(TAG, "开始处理颗粒效果（同步），图片尺寸: ${width}x${height}")
            
            val grainSeed = Random.nextFloat() * 1000f
            processImageDirect(bitmap, config, grainSeed)
        } catch (e: Exception) {
            Log.e(TAG, "颗粒处理失败", e)
            null
        }
    }
}
