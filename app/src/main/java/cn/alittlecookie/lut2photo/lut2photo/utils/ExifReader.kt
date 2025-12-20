package cn.alittlecookie.lut2photo.lut2photo.utils

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import cn.alittlecookie.lut2photo.lut2photo.model.ExifVariables
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * EXIF信息读取工具类
 */
class ExifReader(private val context: Context) {

    /**
     * 从URI读取EXIF信息
     */
    fun readExifFromUri(uri: Uri): Map<String, String> {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            inputStream?.use { stream ->
                val exif = ExifInterface(stream)
                extractExifData(exif)
            } ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * 从文件路径读取EXIF信息
     */
    fun readExifFromPath(filePath: String): Map<String, String> {
        return try {
            val exif = ExifInterface(filePath)
            extractExifData(exif)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * 提取EXIF数据
     */
    private fun extractExifData(exif: ExifInterface): Map<String, String> {
        val exifData = mutableMapOf<String, String>()

        // ISO感光度
        val iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
        exifData[ExifVariables.ISO] = iso ?: "未知"

        // 光圈值
        val aperture = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)
        exifData[ExifVariables.APERTURE] =
            if (aperture > 0) String.format("%.1f", aperture) else "未知"

        // 快门速度
        val shutterSpeed = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
        exifData[ExifVariables.SHUTTER] = formatShutterSpeed(shutterSpeed)

        // 相机型号
        val cameraModel = exif.getAttribute(ExifInterface.TAG_MODEL)
        exifData[ExifVariables.CAMERA_MODEL] = cameraModel ?: "未知"

        // 镜头型号
        val lensModel = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)
        exifData[ExifVariables.LENS_MODEL] = lensModel ?: "未知"

        // 焦距
        val focalLength = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
        exifData[ExifVariables.FOCAL_LENGTH] =
            if (focalLength > 0) "${focalLength.toInt()}mm" else "未知"

        // 曝光补偿
        val exposureCompensation =
            exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, 0.0)
        exifData[ExifVariables.EXPOSURE_COMPENSATION] = if (exposureCompensation != 0.0) {
            String.format("%.1fEV", exposureCompensation)
        } else "0EV"

        // 白平衡
        val whiteBalance = exif.getAttributeInt(ExifInterface.TAG_WHITE_BALANCE, -1)
        exifData[ExifVariables.WHITE_BALANCE] = when (whiteBalance) {
            ExifInterface.WHITE_BALANCE_AUTO.toInt() -> "自动"
            ExifInterface.WHITE_BALANCE_MANUAL.toInt() -> "手动"
            else -> "未知"
        }

        // 闪光灯
        val flashValue = exif.getAttributeInt(ExifInterface.TAG_FLASH, -1)
        exifData[ExifVariables.FLASH] = when {
            flashValue != -1 && (flashValue and ExifInterface.FLAG_FLASH_FIRED.toInt()) != 0 -> "开启"
            else -> "关闭"
        }

        // 拍摄日期和时间
        val dateTime = exif.getAttribute(ExifInterface.TAG_DATETIME)
        if (dateTime != null) {
            try {
                val inputFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.getDefault())
                val date = inputFormat.parse(dateTime)
                if (date != null) {
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    exifData[ExifVariables.DATE] = dateFormat.format(date)
                    exifData[ExifVariables.TIME] = timeFormat.format(date)
                }
            } catch (e: Exception) {
                exifData[ExifVariables.DATE] = "未知"
                exifData[ExifVariables.TIME] = "未知"
            }
        } else {
            exifData[ExifVariables.DATE] = "未知"
            exifData[ExifVariables.TIME] = "未知"
        }

        return exifData
    }

    /**
     * 格式化快门速度
     */
    private fun formatShutterSpeed(shutterSpeed: String?): String {
        if (shutterSpeed == null) return "未知"

        return try {
            val speed = shutterSpeed.toDouble()
            when {
                speed >= 1.0 -> "${speed.toInt()}s"
                speed > 0 -> {
                    val fraction = 1.0 / speed
                    "1/${fraction.toInt()}s"
                }

                else -> "未知"
            }
        } catch (e: Exception) {
            shutterSpeed
        }
    }

    /**
     * 替换文本中的EXIF变量
     */
    fun replaceExifVariables(text: String, exifData: Map<String, String>): String {
        var result = text
        exifData.forEach { (variable, value) ->
            result = result.replace(variable, value)
        }
        return result
    }

    /**
     * 替换文本中的EXIF和LUT变量
     */
    fun replaceExifVariables(
        text: String,
        exifData: Map<String, String>,
        lut1Name: String? = null,
        lut2Name: String? = null,
        lut1Strength: Float? = null,
        lut2Strength: Float? = null
    ): String {
        var result = text

        // 替换EXIF变量
        exifData.forEach { (variable, value) ->
            result = result.replace(variable, value)
        }

        // 替换LUT变量
        result = result.replace(ExifVariables.LUT1, lut1Name ?: "无")
        result = result.replace(ExifVariables.LUT2, lut2Name ?: "无")
        result = result.replace(
            ExifVariables.LUT1_STRENGTH,
            lut1Strength?.let {
                val value = if (it <= 1.0f) it * 100 else it
                String.format("%.0f%%", value)
            } ?: "0%")
        result = result.replace(
            ExifVariables.LUT2_STRENGTH,
            lut2Strength?.let {
                val value = if (it <= 1.0f) it * 100 else it
                String.format("%.0f%%", value)
            } ?: "0%")

        return result
    }
}