package cn.alittlecookie.lut2photo.lut2photo.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import cn.alittlecookie.lut2photo.lut2photo.R
import cn.alittlecookie.lut2photo.lut2photo.utils.HardwareAcceleratedProcessor
import cn.alittlecookie.lut2photo.lut2photo.utils.LutUtils
import cn.alittlecookie.lut2photo.lut2photo.utils.PreferencesManager
import cn.alittlecookie.lut2photo.lut2photo.utils.WatermarkUtils
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class ImagePreviewCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val imageView: ImageView
    private val titleTextView: TextView

    // 使用硬件加速优化的线程池
    private val executor = ThreadPoolExecutor(
        2, // 核心线程数
        4, // 最大线程数
        60L, TimeUnit.SECONDS, // 空闲线程存活时间
        LinkedBlockingQueue<Runnable>(10), // 任务队列
        { r -> Thread(r, "ImagePreview-Worker").apply { priority = Thread.NORM_PRIORITY + 1 } }
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    private val preferencesManager = PreferencesManager(context)

    // 防抖机制
    private var updateRunnable: Runnable? = null
    private val debounceDelayMs = 200L

    // 当前显示的图片路径
    private var currentImagePath: String? = null

    init {
        val view = LayoutInflater.from(context).inflate(R.layout.card_image_preview, this, true)
        imageView = view.findViewById(R.id.imageView)
        titleTextView = view.findViewById(R.id.titleTextView)

        // 设置默认占位图
        showPlaceholder()
    }

    fun setTitle(title: String) {
        titleTextView.text = title
    }

    fun showPlaceholder() {
        imageView.setImageResource(R.drawable.ic_image_placeholder)
        currentImagePath = null
    }

    fun showPlaceholder(message: String) {
        imageView.setImageResource(R.drawable.ic_image_placeholder)
        currentImagePath = null
        // 可以考虑在UI中显示message，这里暂时只设置占位图
    }

    // 存储当前的LUT参数
    private var currentLutParams: LutParams? = null
    private var currentWatermarkParams: WatermarkParams? = null
    private var currentImageUri: android.net.Uri? = null

    data class LutParams(
        val lut1Path: String?,
        val lut2Path: String?,
        val lut1Strength: Float,
        val lut2Strength: Float,
        val quality: Int,
        val ditherType: String
    )

    data class WatermarkParams(
        val enabled: Boolean,
        val text: String,
        val position: String,
        val size: Int,
        val opacity: Float,
        val color: String
    )

    fun setLutParams(
        lut1Path: String?,
        lut2Path: String?,
        lut1Strength: Float,
        lut2Strength: Float,
        quality: Int,
        ditherType: String
    ) {
        currentLutParams =
            LutParams(lut1Path, lut2Path, lut1Strength, lut2Strength, quality, ditherType)
        updatePreviewIfReady()
    }

    fun setWatermarkParams(
        enabled: Boolean,
        text: String,
        position: String,
        size: Int,
        opacity: Float,
        color: String
    ) {
        currentWatermarkParams = WatermarkParams(enabled, text, position, size, opacity, color)
        updatePreviewIfReady()
    }

    fun setImageUri(uri: android.net.Uri) {
        currentImageUri = uri
        updatePreviewIfReady()
    }

    private fun updatePreviewIfReady() {
        val imageUri = currentImageUri
        val lutParams = currentLutParams
        val watermarkParams = currentWatermarkParams

        if (imageUri != null) {
            val imagePath = imageUri.path
            updatePreview(
                imagePath = imagePath,
                lutPath = lutParams?.lut1Path,
                lutIntensity = lutParams?.lut1Strength ?: 1.0f,
                secondLutPath = lutParams?.lut2Path,
                secondLutIntensity = lutParams?.lut2Strength ?: 1.0f,
                quality = lutParams?.quality ?: 100,
                ditherType = lutParams?.ditherType ?: "none",
                watermarkEnabled = watermarkParams?.enabled ?: false
            )
        }
    }

    fun updatePreview(
        imagePath: String?,
        lutPath: String? = null,
        lutIntensity: Float = 1.0f,
        secondLutPath: String? = null,
        secondLutIntensity: Float = 1.0f,
        quality: Int = 100,
        ditherType: String = "none",
        watermarkEnabled: Boolean = false
    ) {
        // 取消之前的更新任务
        updateRunnable?.let { mainHandler.removeCallbacks(it) }

        // 创建新的更新任务
        updateRunnable = Runnable {
            if (imagePath.isNullOrEmpty()) {
                showPlaceholder()
                return@Runnable
            }

            currentImagePath = imagePath

            // 在后台线程处理图片
            executor.execute {
                try {
                    val processedBitmap = processImage(
                        imagePath,
                        lutPath,
                        lutIntensity,
                        secondLutPath,
                        secondLutIntensity,
                        quality,
                        ditherType,
                        watermarkEnabled
                    )

                    // 在主线程更新UI
                    mainHandler.post {
                        if (currentImagePath == imagePath) {
                            imageView.setImageBitmap(processedBitmap)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    mainHandler.post {
                        showPlaceholder()
                    }
                }
            }
        }

        // 延迟执行更新任务（防抖）
        mainHandler.postDelayed(updateRunnable!!, debounceDelayMs)
    }

    private fun processImage(
        imagePath: String,
        lutPath: String?,
        lutIntensity: Float,
        secondLutPath: String?,
        secondLutIntensity: Float,
        quality: Int,
        ditherType: String,
        watermarkEnabled: Boolean
    ): Bitmap? {
        val file = File(imagePath)
        if (!file.exists()) return null

        // 加载原始图片并生成缩略图
        val originalBitmap = loadThumbnail(imagePath) ?: return null

        // 使用硬件加速的渲染管线处理图片效果链
        return try {
            // 构建效果链
            val effects = mutableListOf<HardwareAcceleratedProcessor.ImageEffect>()

            // 添加第一个LUT效果
            if (!lutPath.isNullOrEmpty() && File(lutPath).exists()) {
                effects.add(
                    HardwareAcceleratedProcessor.ImageEffect(
                        type = HardwareAcceleratedProcessor.EffectType.LUT,
                        lutPath = lutPath,
                        intensity = lutIntensity
                    )
                )
            }

            // 添加第二个LUT效果
            if (!secondLutPath.isNullOrEmpty() && File(secondLutPath).exists()) {
                effects.add(
                    HardwareAcceleratedProcessor.ImageEffect(
                        type = HardwareAcceleratedProcessor.EffectType.LUT,
                        lutPath = secondLutPath,
                        intensity = secondLutIntensity
                    )
                )
            }

            // 添加水印效果
            if (watermarkEnabled) {
                val watermarkConfig = preferencesManager.getWatermarkConfig()
                val lut1Name = lutPath?.let { File(it).nameWithoutExtension }
                val lut2Name = secondLutPath?.let { File(it).nameWithoutExtension }
                effects.add(
                    HardwareAcceleratedProcessor.ImageEffect(
                        type = HardwareAcceleratedProcessor.EffectType.WATERMARK,
                        watermarkConfig = watermarkConfig,
                        lut1Name = lut1Name,
                        lut2Name = lut2Name,
                        lut1Strength = lutIntensity,
                        lut2Strength = secondLutIntensity
                    )
                )
            }

            // 如果没有效果，直接返回原图
            if (effects.isEmpty()) {
                return originalBitmap
            }

            // 使用硬件加速处理器批量处理效果
            if (HardwareAcceleratedProcessor.isHardwareAccelerationSupported()) {
                HardwareAcceleratedProcessor.processImageWithHardwareAcceleration(
                    originalBitmap,
                    effects,
                    context
                )
            } else {
                // 回退到传统处理方式
                processImageTraditional(
                    originalBitmap,
                    lutPath,
                    lutIntensity,
                    secondLutPath,
                    secondLutIntensity,
                    watermarkEnabled
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            originalBitmap
        }
    }

    private fun loadThumbnail(imagePath: String): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(imagePath, options)

            // 根据屏幕分辨率动态计算目标尺寸
            val displayMetrics = context.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels
            val density = displayMetrics.density

            // 目标尺寸为屏幕宽度的1/3，但不超过600dp，不小于200dp
            val targetSizeDp = Math.min(Math.max(screenWidth / density / 3, 200f), 600f)
            val targetSize = (targetSizeDp * density).toInt()

            // 计算合适的缩放比例，保持宽高比
            val scaleFactor = Math.max(
                options.outWidth / targetSize,
                options.outHeight / targetSize
            )

            options.inJustDecodeBounds = false
            options.inSampleSize = Math.max(1, scaleFactor)
            options.inPreferredConfig = Bitmap.Config.RGB_565 // 使用更节省内存的格式

            val bitmap = BitmapFactory.decodeFile(imagePath, options)

            // 如果需要进一步缩放以精确匹配目标尺寸
            if (bitmap != null && (bitmap.width > targetSize * 1.5 || bitmap.height > targetSize * 1.5)) {
                val finalScale = Math.min(
                    targetSize.toFloat() / bitmap.width,
                    targetSize.toFloat() / bitmap.height
                )

                val finalWidth = (bitmap.width * finalScale).toInt()
                val finalHeight = (bitmap.height * finalScale).toInt()

                val scaledBitmap = Bitmap.createScaledBitmap(bitmap, finalWidth, finalHeight, true)
                if (scaledBitmap != bitmap) {
                    bitmap.recycle()
                }
                scaledBitmap
            } else {
                bitmap
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 传统图片处理方式（回退方案）
     */
    private fun processImageTraditional(
        originalBitmap: Bitmap,
        lutPath: String?,
        lutIntensity: Float,
        secondLutPath: String?,
        secondLutIntensity: Float,
        watermarkEnabled: Boolean
    ): Bitmap {
        var processedBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)

        // 应用第一个LUT
        if (!lutPath.isNullOrEmpty() && File(lutPath).exists()) {
            val lutBitmap = LutUtils.applyLut(processedBitmap, lutPath, lutIntensity)
            if (lutBitmap != processedBitmap) {
                processedBitmap.recycle()
                processedBitmap = lutBitmap
            }
        }

        // 应用第二个LUT
        if (!secondLutPath.isNullOrEmpty() && File(secondLutPath).exists()) {
            val secondLutBitmap =
                LutUtils.applyLut(processedBitmap, secondLutPath, secondLutIntensity)
            if (secondLutBitmap != processedBitmap) {
                processedBitmap.recycle()
                processedBitmap = secondLutBitmap
            }
        }

        // 应用水印
        if (watermarkEnabled) {
            val watermarkBitmap = applyWatermark(
                processedBitmap,
                lutPath,
                secondLutPath,
                lutIntensity,
                secondLutIntensity
            )
            if (watermarkBitmap != processedBitmap) {
                processedBitmap.recycle()
                processedBitmap = watermarkBitmap
            }
        }

        return processedBitmap
    }

    private fun applyWatermark(
        bitmap: Bitmap,
        lutPath: String? = null,
        secondLutPath: String? = null,
        lutIntensity: Float = 1.0f,
        secondLutIntensity: Float = 1.0f
    ): Bitmap {
        return try {
            val watermarkConfig = preferencesManager.getWatermarkConfig()
            val lut1Name = lutPath?.let { File(it).nameWithoutExtension }
            val lut2Name = secondLutPath?.let { File(it).nameWithoutExtension }
            WatermarkUtils.addWatermark(
                bitmap,
                watermarkConfig,
                context,
                null, // imageUri - 预览卡片中没有原始URI
                lut1Name,
                lut2Name,
                lutIntensity,
                secondLutIntensity
            )
        } catch (e: Exception) {
            e.printStackTrace()
            bitmap
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        updateRunnable?.let { mainHandler.removeCallbacks(it) }
        executor.shutdown()
    }
}