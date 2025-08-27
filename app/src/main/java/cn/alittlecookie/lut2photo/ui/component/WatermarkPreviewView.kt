package cn.alittlecookie.lut2photo.ui.component

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PaintFlagsDrawFilter
import android.graphics.PointF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import cn.alittlecookie.lut2photo.lut2photo.R
import cn.alittlecookie.lut2photo.lut2photo.databinding.ComponentWatermarkPreviewBinding
import cn.alittlecookie.lut2photo.lut2photo.model.TextFollowDirection
import cn.alittlecookie.lut2photo.lut2photo.model.WatermarkConfig
import cn.alittlecookie.lut2photo.lut2photo.utils.ExifReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 水印预览组件
 * 用于实时显示水印效果预览
 */
class WatermarkPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val PREVIEW_MAX_SIZE = 800 // 预览图最大尺寸（像素）- 提升分辨率
        private const val UPDATE_DELAY = 300L // 防抖延迟时间（毫秒）
    }

    private val binding: ComponentWatermarkPreviewBinding
    private val exifReader = ExifReader(context)
    private val handler = Handler(Looper.getMainLooper())
    private var updateJob: Job? = null
    private var previewUpdateRunnable: Runnable? = null

    // 示例图片
    private var sampleBitmap: Bitmap? = null
    private var backgroundBitmap: Bitmap? = null // 用户选择的背景图片
    private var isCollapsed = false

    // 回调接口
    var onToggleListener: ((isCollapsed: Boolean) -> Unit)? = null
    var configProvider: (() -> WatermarkConfig?)? = null // 配置提供者回调
    var onPreviewClickListener: (() -> Unit)? = null // 预览点击回调

    // 上次的配置，用于检测变化
    private var lastConfig: WatermarkConfig? = null

    init {
        binding = ComponentWatermarkPreviewBinding.inflate(LayoutInflater.from(context), this, true)
        setupViews()
        loadSampleImage()

        // 初始化时显示示例图片
        post {
            binding.imagePreview.setImageBitmap(sampleBitmap)
        }
    }

    private fun setupViews() {
        // 设置折叠/展开按钮点击事件
        binding.buttonTogglePreview.setOnClickListener {
            togglePreview()
        }

        // 设置预览尺寸按钮
        binding.buttonPreviewSize.setOnClickListener {
            cyclePreviewSize()
        }

        // 设置刷新按钮
        binding.buttonRefreshPreview.setOnClickListener {
            refreshPreview()
        }

        // 设置预览图片点击事件
        binding.imagePreview.setOnClickListener {
            onPreviewClickListener?.invoke()
        }

        // 初始状态为展开
        setCollapsed(false)

        // 启动配置变化检测
        startConfigChangeDetection()
    }

    private fun loadSampleImage() {
        try {
            // 使用内置的示例图片或创建一个简单的示例图片
            sampleBitmap = createSampleBitmap()
            if (sampleBitmap == null) {
                sampleBitmap = createPlainSampleBitmap()
            }
        } catch (e: Exception) {
            // 如果创建失败，使用纯色背景
            try {
                sampleBitmap = createPlainSampleBitmap()
            } catch (e2: Exception) {
                // 最后的备用方案：创建一个最基本的Bitmap
                sampleBitmap = createBasicBitmap()
            }
        }
    }

    private fun createSampleBitmap(): Bitmap? {
        return try {
            // 创建一个400x300的示例图片
            val width = 400
            val height = 300
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // 绘制渐变背景
            val paint = Paint().apply {
                isAntiAlias = true
            }

            // 背景色
            paint.color = 0xFF4CAF50.toInt() // 绿色背景
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

            // 绘制一些示例内容
            paint.color = 0xFFFFFFFF.toInt() // 白色文字
            paint.textSize = 24f
            paint.textAlign = Paint.Align.CENTER

            canvas.drawText("示例照片", width / 2f, height / 2f - 20f, paint)

            paint.textSize = 16f
            canvas.drawText("水印预览效果", width / 2f, height / 2f + 20f, paint)

            bitmap
        } catch (e: Exception) {
            null
        }
    }

    private fun createPlainSampleBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            color = 0xFF757575.toInt() // 灰色背景
        }
        canvas.drawRect(0f, 0f, 400f, 300f, paint)
        return bitmap
    }

    private fun createBasicBitmap(): Bitmap {
        return Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
    }

    /**
     * 切换预览区域的折叠状态
     */
    private fun togglePreview() {
        setCollapsed(!isCollapsed)
    }

    /**
     * 设置折叠状态
     */
    private fun setCollapsed(collapsed: Boolean) {
        isCollapsed = collapsed

        if (collapsed) {
            // 折叠状态：只显示标题栏
            binding.layoutPreviewContent.visibility = GONE
            binding.buttonTogglePreview.setIconResource(R.drawable.ic_expand_more)
            binding.textPreviewTitle.text = "水印预览（右侧展开）"
        } else {
            // 展开状态：显示完整预览
            binding.layoutPreviewContent.visibility = VISIBLE
            binding.buttonTogglePreview.setIconResource(R.drawable.ic_expand_less)
            binding.textPreviewTitle.text = "水印预览"

            // 展开时显示示例图片
            if (binding.imagePreview.drawable == null) {
                binding.imagePreview.setImageBitmap(sampleBitmap)
            }
        }

        onToggleListener?.invoke(isCollapsed)
    }

    /**
     * 循环切换预览尺寸
     */
    private fun cyclePreviewSize() {
        val currentParams = binding.imagePreview.layoutParams
        val newSize = when (currentParams.width) {
            (200 * resources.displayMetrics.density).toInt() -> (300 * resources.displayMetrics.density).toInt()
            (300 * resources.displayMetrics.density).toInt() -> (400 * resources.displayMetrics.density).toInt()
            else -> (200 * resources.displayMetrics.density).toInt()
        }

        currentParams.width = newSize
        currentParams.height = (newSize * 0.75f).toInt() // 4:3 比例
        binding.imagePreview.layoutParams = currentParams

        // 更新按钮文字
        val sizeText = when (newSize) {
            (200 * resources.displayMetrics.density).toInt() -> "小"
            (300 * resources.displayMetrics.density).toInt() -> "中"
            else -> "大"
        }
        binding.buttonPreviewSize.text = sizeText
    }

    /**
     * 刷新预览
     */
    private fun refreshPreview() {
        // 如果有回调获取配置，则尝试更新
        val currentConfig = getCurrentConfig()
        if (currentConfig != null) {
            updatePreview(currentConfig)
        } else {
            // 只有在没有配置且没有用户背景图片时才显示示例图片
            if (backgroundBitmap == null) {
                binding.imagePreview.setImageBitmap(sampleBitmap)
            } else {
                // 有用户背景图片时直接显示
                binding.imagePreview.setImageBitmap(backgroundBitmap)
            }
        }
    }

    /**
     * 启动配置变化检测
     */
    private fun startConfigChangeDetection() {
        val configCheckRunnable = object : Runnable {
            override fun run() {
                val currentConfig = getCurrentConfig()
                if (currentConfig != null && hasConfigChanged(currentConfig)) {
                    lastConfig = currentConfig
                    updatePreview(currentConfig)
                }
                // 每500ms检查一次配置变化
                handler.postDelayed(this, 500)
            }
        }
        handler.post(configCheckRunnable)
    }

    /**
     * 检查配置是否发生变化
     */
    private fun hasConfigChanged(newConfig: WatermarkConfig): Boolean {
        val oldConfig = lastConfig ?: return true

        // 检查边框相关配置
        if (oldConfig.borderTopWidth != newConfig.borderTopWidth ||
            oldConfig.borderBottomWidth != newConfig.borderBottomWidth ||
            oldConfig.borderLeftWidth != newConfig.borderLeftWidth ||
            oldConfig.borderRightWidth != newConfig.borderRightWidth ||
            oldConfig.borderColor != newConfig.borderColor
        ) {
            return true
        }

        // 检查其他关键配置
        return oldConfig.enableTextWatermark != newConfig.enableTextWatermark ||
                oldConfig.enableImageWatermark != newConfig.enableImageWatermark ||
                oldConfig.textContent != newConfig.textContent ||
                oldConfig.imagePath != newConfig.imagePath ||
                oldConfig.textPositionX != newConfig.textPositionX ||
                oldConfig.textPositionY != newConfig.textPositionY ||
                oldConfig.imagePositionX != newConfig.imagePositionX ||
                oldConfig.imagePositionY != newConfig.imagePositionY ||
                oldConfig.textSize != newConfig.textSize ||
                oldConfig.imageSize != newConfig.imageSize ||
                oldConfig.textOpacity != newConfig.textOpacity ||
                oldConfig.imageOpacity != newConfig.imageOpacity ||
                oldConfig.textColor != newConfig.textColor ||
                oldConfig.enableTextFollowMode != newConfig.enableTextFollowMode ||
                oldConfig.textFollowDirection != newConfig.textFollowDirection ||
                oldConfig.textImageSpacing != newConfig.textImageSpacing
    }

    /**
     * 更新水印预览
     * @param config 水印配置
     */
    fun updatePreview(config: WatermarkConfig) {
        // 取消之前的更新任务
        previewUpdateRunnable?.let { handler.removeCallbacks(it) }
        updateJob?.cancel()

        // 使用防抖机制，避免频繁更新
        previewUpdateRunnable = Runnable {
            updateJob = CoroutineScope(Dispatchers.Main).launch {
                try {
                    val previewBitmap = generatePreviewBitmap(config)
                    binding.imagePreview.setImageBitmap(previewBitmap)
                } catch (e: Exception) {
                    // 显示错误时保持当前图片，避免闪烁
                    // 只有在当前没有任何图片时才显示示例图片
                    if (binding.imagePreview.drawable == null) {
                        val fallbackBitmap = backgroundBitmap ?: sampleBitmap
                        binding.imagePreview.setImageBitmap(fallbackBitmap)
                    }
                }
            }
        }

        handler.postDelayed(previewUpdateRunnable!!, UPDATE_DELAY)
    }

    /**
     * 设置背景图片
     */
    fun setBackgroundImage(bitmap: Bitmap) {
        backgroundBitmap = bitmap
        // 立即显示背景图片，避免闪烁
        binding.imagePreview.setImageBitmap(bitmap)

        // 重新生成预览（如果有配置）
        val config = getCurrentConfig()
        if (config != null) {
            updatePreview(config)
        }
    }

    /**
     * 生成预览图片
     */
    private suspend fun generatePreviewBitmap(config: WatermarkConfig): Bitmap =
        withContext(Dispatchers.Default) {
            val baseBitmap = backgroundBitmap ?: sampleBitmap ?: createBasicBitmap()

            // 创建更高分辨率的预览图片，保持宽高比
            val aspectRatio = baseBitmap.width.toFloat() / baseBitmap.height.toFloat()
            val previewWidth: Int
            val previewHeight: Int

            if (aspectRatio > 1) {
                // 横图：以宽度为准
                previewWidth = PREVIEW_MAX_SIZE
                previewHeight = (PREVIEW_MAX_SIZE / aspectRatio).toInt()
            } else {
                // 竖图：以高度为准
                previewHeight = PREVIEW_MAX_SIZE
                previewWidth = (PREVIEW_MAX_SIZE * aspectRatio).toInt()
            }

            val scaledBitmap = Bitmap.createScaledBitmap(
                baseBitmap,
                previewWidth,
                previewHeight,
                true // 使用高质量缩放
            )

            // 首先添加边框（如果有配置）
            val bitmapWithBorder = addBorderToPreview(scaledBitmap, config)

            // 在主线程更新ImageView尺寸以适应新的宽高比
            withContext(Dispatchers.Main) {
                updateImageViewSize(bitmapWithBorder)
            }

            // 检查是否有水印内容需要显示
            val hasAnyWatermark = config.enableTextWatermark || config.enableImageWatermark ||
                    config.textContent.isNotEmpty() || config.imagePath.isNotEmpty()

            if (!hasAnyWatermark) {
                return@withContext bitmapWithBorder
            }

            val resultBitmap = bitmapWithBorder.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(resultBitmap)

            // 设置高质量渲染
            canvas.setDrawFilter(
                android.graphics.PaintFlagsDrawFilter(
                    0, Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
                )
            )

            // 模拟EXIF数据
            val mockExifData = mapOf(
                "ISO" to "100",
                "APERTURE" to "2.8",
                "SHUTTER" to "1/60",
                "CAMERA_MODEL" to "示例相机",
                "LENS_MODEL" to "示例镜头",
                "DATE" to "2024-01-01",
                "TIME" to "12:00:00",
                "FOCAL_LENGTH" to "50mm",
                "EXPOSURE_COMPENSATION" to "0",
                "WHITE_BALANCE" to "Auto",
                "FLASH" to "Off"
            )

            // 绘制水印
            if (config.enableTextFollowMode && config.enableTextWatermark && config.enableImageWatermark &&
                config.textContent.isNotEmpty() && config.imagePath.isNotEmpty()
            ) {
                drawWatermarksInFollowMode(canvas, resultBitmap, config, mockExifData)
            } else {
                drawWatermarksInNormalMode(canvas, resultBitmap, config, mockExifData)
            }

            resultBitmap
        }

    /**
     * 普通模式下绘制水印
     */
    private suspend fun drawWatermarksInNormalMode(
        canvas: Canvas,
        bitmap: Bitmap,
        config: WatermarkConfig,
        exifData: Map<String, String>
    ) {
        // 绘制图片水印（如果启用且有路径）
        if ((config.enableImageWatermark || config.imagePath.isNotEmpty()) && config.imagePath.isNotEmpty()) {
            drawImageWatermark(canvas, bitmap, config)
        }

        // 绘制文字水印（如果启用且有内容）
        if ((config.enableTextWatermark || config.textContent.isNotEmpty()) && config.textContent.isNotEmpty()) {
            val processedText = replaceExifVariables(config.textContent, exifData)
            drawTextWatermark(canvas, bitmap, config, processedText)
        }

        // 如果没有任何水印，显示一个示例文字水印
        if (config.textContent.isEmpty() && config.imagePath.isEmpty()) {
            val demoText = "示例水印: ISO 100 f/2.8 1/60s"
            val demoConfig = config.copy(
                textContent = demoText,
                enableTextWatermark = true
            )
            drawTextWatermark(canvas, bitmap, demoConfig, demoText)
        }
    }

    /**
     * 跟随模式下绘制水印
     */
    private suspend fun drawWatermarksInFollowMode(
        canvas: Canvas,
        bitmap: Bitmap,
        config: WatermarkConfig,
        exifData: Map<String, String>
    ) {
        // 先绘制图片水印
        val imagePosition = drawImageWatermark(canvas, bitmap, config)

        if (imagePosition != null) {
            // 根据图片水印位置绘制文字水印
            val processedText = replaceExifVariables(config.textContent, exifData)
            drawTextWatermarkInFollowMode(canvas, bitmap, config, processedText, imagePosition)
        }
    }

    /**
     * 绘制图片水印
     * @return 图片水印的位置信息
     */
    private suspend fun drawImageWatermark(
        canvas: Canvas,
        bitmap: Bitmap,
        config: WatermarkConfig
    ): PointF? {
        if (config.imagePath.isEmpty()) return null

        try {
            val imageFile = File(config.imagePath)
            if (!imageFile.exists()) return null

            val watermarkBitmap = BitmapFactory.decodeFile(config.imagePath) ?: return null

            // 计算水印图片大小
            val imageWatermarkSize = (bitmap.width * config.imageSize / 100).toInt()
            val scaledWatermark = Bitmap.createScaledBitmap(
                watermarkBitmap,
                imageWatermarkSize,
                (imageWatermarkSize * watermarkBitmap.height / watermarkBitmap.width),
                true
            )

            // 计算位置
            val position =
                calculateWatermarkPosition(bitmap, config.imagePositionX, config.imagePositionY)
            val imageX = position.x - scaledWatermark.width / 2f
            val imageY = position.y - scaledWatermark.height / 2f

            // 绘制
            val paint = Paint().apply {
                alpha = (config.imageOpacity * 255 / 100).toInt()
                isAntiAlias = true
                isFilterBitmap = true
                isDither = true
            }
            canvas.drawBitmap(scaledWatermark, imageX, imageY, paint)

            return position
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * 绘制文字水印
     */
    private fun drawTextWatermark(
        canvas: Canvas,
        bitmap: Bitmap,
        config: WatermarkConfig,
        text: String
    ) {
        val position =
            calculateWatermarkPosition(bitmap, config.textPositionX, config.textPositionY)
        drawTextAtPosition(canvas, bitmap, config, text, position.x, position.y)
    }

    /**
     * 跟随模式下绘制文字水印
     */
    private fun drawTextWatermarkInFollowMode(
        canvas: Canvas,
        bitmap: Bitmap,
        config: WatermarkConfig,
        text: String,
        imagePosition: PointF
    ) {
        // 这里简化实现，实际应该根据图片水印大小计算
        val spacing = bitmap.height * config.textImageSpacing / 100f

        val textPosition = when (config.textFollowDirection) {
            TextFollowDirection.TOP -> PointF(imagePosition.x, imagePosition.y - spacing)
            TextFollowDirection.BOTTOM -> PointF(imagePosition.x, imagePosition.y + spacing)
            TextFollowDirection.LEFT -> PointF(imagePosition.x - spacing, imagePosition.y)
            TextFollowDirection.RIGHT -> PointF(imagePosition.x + spacing, imagePosition.y)
        }

        drawTextAtPosition(canvas, bitmap, config, text, textPosition.x, textPosition.y)
    }

    /**
     * 在指定位置绘制文字
     */
    private fun drawTextAtPosition(
        canvas: Canvas,
        bitmap: Bitmap,
        config: WatermarkConfig,
        text: String,
        x: Float,
        y: Float
    ) {
        val paint = Paint().apply {
            color = try {
                android.graphics.Color.parseColor(config.textColor)
            } catch (e: Exception) {
                android.graphics.Color.WHITE
            }
            alpha = (config.textOpacity * 255 / 100).toInt()
            isAntiAlias = true
            isFilterBitmap = true
            isDither = true
            isSubpixelText = true
            textSize = bitmap.width * config.textSize / 100f
            textAlign = when (config.textAlignment) {
                cn.alittlecookie.lut2photo.lut2photo.model.TextAlignment.LEFT -> Paint.Align.LEFT
                cn.alittlecookie.lut2photo.lut2photo.model.TextAlignment.CENTER -> Paint.Align.CENTER
                cn.alittlecookie.lut2photo.lut2photo.model.TextAlignment.RIGHT -> Paint.Align.RIGHT
            }

            // 加载自定义字体
            if (config.fontPath.isNotEmpty()) {
                try {
                    val fontFile = File(config.fontPath)
                    if (fontFile.exists()) {
                        typeface = Typeface.createFromFile(fontFile)
                    }
                } catch (e: Exception) {
                    // 使用默认字体
                }
            }
        }

        // 确保文字在可见区域内
        val adjustedY = when {
            y < paint.textSize -> paint.textSize // 防止文字超出上边界
            y > bitmap.height - 10 -> bitmap.height - 10f // 防止文字超出下边界
            else -> y
        }

        val adjustedX = when (paint.textAlign) {
            Paint.Align.LEFT -> x.coerceAtLeast(10f) // 左对齐时保证不超出左边界
            Paint.Align.RIGHT -> x.coerceAtMost(bitmap.width - 10f) // 右对齐时保证不超出右边界
            else -> x // 居中对齐
        }

        canvas.drawText(text, adjustedX, adjustedY, paint)
    }

    /**
     * 计算水印位置
     */
    private fun calculateWatermarkPosition(
        bitmap: Bitmap,
        xPercent: Float,
        yPercent: Float
    ): PointF {
        val x = bitmap.width * xPercent / 100
        val y = bitmap.height * yPercent / 100
        return PointF(x, y)
    }

    /**
     * 替换EXIF变量
     */
    private fun replaceExifVariables(text: String, exifData: Map<String, String>): String {
        var result = text
        exifData.forEach { (key, value) ->
            result = result.replace("{$key}", value)
        }
        return result
    }

    /**
     * 获取当前配置（通过外部回调获取）
     */
    private fun getCurrentConfig(): WatermarkConfig? {
        return configProvider?.invoke()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // 取消预览更新任务
        previewUpdateRunnable?.let { handler.removeCallbacks(it) }
        updateJob?.cancel()

        // 停止配置变化检测
        handler.removeCallbacksAndMessages(null)

        // 回收示例图片
        sampleBitmap?.let {
            if (!it.isRecycled) {
                it.recycle()
            }
        }
        sampleBitmap = null
    }

    /**
     * 为预览图片添加边框
     */
    private fun addBorderToPreview(bitmap: Bitmap, config: WatermarkConfig): Bitmap {
        // 检查是否有任意边框宽度大于0
        if (config.borderTopWidth <= 0 && config.borderBottomWidth <= 0 &&
            config.borderLeftWidth <= 0 && config.borderRightWidth <= 0
        ) {
            return bitmap
        }

        val shortSide = kotlin.math.min(bitmap.width, bitmap.height)

        // 计算四个方向的边框宽度（基于图片短边的百分比）
        // 确保边框宽度至少为1像素，避免过小的边框在预览中不可见
        val borderTopPx = kotlin.math.max(1, (shortSide * config.borderTopWidth / 100).toInt())
        val borderBottomPx =
            kotlin.math.max(1, (shortSide * config.borderBottomWidth / 100).toInt())
        val borderLeftPx = kotlin.math.max(1, (shortSide * config.borderLeftWidth / 100).toInt())
        val borderRightPx = kotlin.math.max(1, (shortSide * config.borderRightWidth / 100).toInt())

        // 如果所有边框都是1像素（即原始配置为0），则不添加边框
        if (config.borderTopWidth <= 0 && config.borderBottomWidth <= 0 &&
            config.borderLeftWidth <= 0 && config.borderRightWidth <= 0
        ) {
            return bitmap
        }

        // 计算新的图片尺寸
        val newWidth = bitmap.width + borderLeftPx + borderRightPx
        val newHeight = bitmap.height + borderTopPx + borderBottomPx

        // 检查新尺寸是否合理（防止边框过大导致内存问题）
        val maxDimension = kotlin.math.max(newWidth, newHeight)
        if (maxDimension > PREVIEW_MAX_SIZE * 2) {
            // 如果边框后的尺寸过大，按比例缩小边框
            val scale = (PREVIEW_MAX_SIZE * 2).toFloat() / maxDimension
            val scaledBorderTopPx = (borderTopPx * scale).toInt()
            val scaledBorderBottomPx = (borderBottomPx * scale).toInt()
            val scaledBorderLeftPx = (borderLeftPx * scale).toInt()
            val scaledBorderRightPx = (borderRightPx * scale).toInt()

            return createBitmapWithBorder(
                bitmap, scaledBorderTopPx, scaledBorderBottomPx,
                scaledBorderLeftPx, scaledBorderRightPx, config
            )
        }

        return createBitmapWithBorder(
            bitmap, borderTopPx, borderBottomPx,
            borderLeftPx, borderRightPx, config
        )
    }

    /**
     * 更新ImageView尺寸以适应新的图片宽高比
     */
    private fun updateImageViewSize(bitmap: Bitmap) {
        val imageView = binding.imagePreview
        val currentParams = imageView.layoutParams

        // 获取当前ImageView的最大尺寸
        val maxWidth = currentParams.width
        val maxHeight = currentParams.height

        // 如果当前是固定尺寸，则根据bitmap的宽高比调整
        if (maxWidth > 0 && maxHeight > 0) {
            val bitmapAspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
            val viewAspectRatio = maxWidth.toFloat() / maxHeight.toFloat()

            if (bitmapAspectRatio > viewAspectRatio) {
                // 图片更宽，以宽度为准
                currentParams.width = maxWidth
                currentParams.height = (maxWidth / bitmapAspectRatio).toInt()
            } else {
                // 图片更高，以高度为准
                currentParams.height = maxHeight
                currentParams.width = (maxHeight * bitmapAspectRatio).toInt()
            }

            imageView.layoutParams = currentParams
        }
    }

    /**
     * 创建带边框的Bitmap
     */
    private fun createBitmapWithBorder(
        bitmap: Bitmap,
        borderTopPx: Int,
        borderBottomPx: Int,
        borderLeftPx: Int,
        borderRightPx: Int,
        config: WatermarkConfig
    ): Bitmap {
        val newWidth = bitmap.width + borderLeftPx + borderRightPx
        val newHeight = bitmap.height + borderTopPx + borderBottomPx

        try {
            val resultBitmap = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(resultBitmap)

            // 设置高质量渲染
            canvas.setDrawFilter(
                PaintFlagsDrawFilter(
                    0, Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
                )
            )

            // 绘制边框背景
            val borderPaint = Paint().apply {
                color = config.getBorderColorInt()
                style = Paint.Style.FILL
                isAntiAlias = true
            }

            // 绘制整个背景
            canvas.drawRect(0f, 0f, newWidth.toFloat(), newHeight.toFloat(), borderPaint)

            // 绘制原图（放置在边框内）
            val imagePaint = Paint().apply {
                isAntiAlias = true
                isFilterBitmap = true
                isDither = true
            }
            canvas.drawBitmap(
                bitmap,
                borderLeftPx.toFloat(),
                borderTopPx.toFloat(),
                imagePaint
            )

            return resultBitmap
        } catch (e: Exception) {
            // 如果创建边框失败，返回原图
            return bitmap
        }
    }

    /**
     * 强制初始化预览，显示示例图片
     */
    fun forceInitialPreview() {
        // 显示基本示例图片
        binding.imagePreview.setImageBitmap(sampleBitmap)

        // 如果有配置提供者，尝试获取并更新
        val config = getCurrentConfig()
        if (config != null) {
            updatePreview(config)
        } else {
            // 如果没有配置，使用默认配置显示示例水印
            val demoConfig = WatermarkConfig(
                isEnabled = true,
                enableTextWatermark = true,
                textContent = "示例水印: ISO 100 f/2.8 1/60s",
                textColor = "#FFFFFF",
                textSize = 3f,
                textPositionX = 50f,
                textPositionY = 90f,
                textOpacity = 80f
            )
            updatePreview(demoConfig)
        }
    }
}