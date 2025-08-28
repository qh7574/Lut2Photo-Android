package cn.alittlecookie.lut2photo.lut2photo.utils

import android.content.Context
import android.graphics.Bitmap
import cn.alittlecookie.lut2photo.lut2photo.core.WatermarkProcessor
import cn.alittlecookie.lut2photo.lut2photo.model.WatermarkConfig
import kotlinx.coroutines.runBlocking

/**
 * 水印工具类，提供简化的水印处理接口
 */
object WatermarkUtils {

    /**
     * 为图片添加水印
     * @param bitmap 原始图片
     * @param config 水印配置
     * @param context 上下文，用于创建WatermarkProcessor
     * @param imageUri 图片URI，用于读取EXIF信息
     * @param lut1Name LUT1文件名
     * @param lut2Name LUT2文件名
     * @param lut1Strength LUT1强度
     * @param lut2Strength LUT2强度
     * @return 添加水印后的图片
     */
    fun addWatermark(
        bitmap: Bitmap,
        config: WatermarkConfig,
        context: Context,
        imageUri: android.net.Uri? = null,
        lut1Name: String? = null,
        lut2Name: String? = null,
        lut1Strength: Float? = null,
        lut2Strength: Float? = null
    ): Bitmap {
        return try {
            if (!config.isEnabled) {
                return bitmap
            }

            // 使用WatermarkProcessor处理水印
            val processor = WatermarkProcessor(context)
            runBlocking {
                processor.addWatermark(
                    bitmap,
                    config,
                    imageUri,
                    lut1Name,
                    lut2Name,
                    lut1Strength,
                    lut2Strength
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            bitmap
        }
    }
}