package cn.alittlecookie.lut2photo.lut2photo.core

import android.graphics.Bitmap
import java.io.InputStream

/**
 * LUT处理器统一接口
 * 支持CPU和GPU两种处理方式
 */
interface ILutProcessor {

    /**
     * 处理参数
     */
    data class ProcessingParams(
        val strength: Float = 1.0f,
        val quality: Int = 90,
        val ditherType: DitherType = DitherType.NONE
    )

    /**
     * 抖动类型
     */
    enum class DitherType {
        NONE,
        FLOYD_STEINBERG,
        RANDOM
    }

    /**
     * 处理器类型
     */
    enum class ProcessorType {
        CPU,
        GPU
    }

    /**
     * 获取处理器类型
     */
    fun getProcessorType(): ProcessorType

    /**
     * 检查处理器是否可用
     */
    suspend fun isAvailable(): Boolean

    /**
     * 加载LUT文件
     * @param inputStream LUT文件输入流
     * @return 是否加载成功
     */
    suspend fun loadCubeLut(inputStream: InputStream): Boolean

    /**
     * 处理图片
     * @param bitmap 输入图片
     * @param params 处理参数
     * @return 处理后的图片，失败返回null
     */
    suspend fun processImage(bitmap: Bitmap, params: ProcessingParams): Bitmap?

    /**
     * 释放资源
     */
    suspend fun release()

    /**
     * 获取处理器信息
     */
    fun getProcessorInfo(): String
}