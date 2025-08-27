package cn.alittlecookie.lut2photo.lut2photo.utils

import android.content.Context
import android.net.Uri
import cn.alittlecookie.lut2photo.lut2photo.model.WatermarkConfig
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 水印配置管理器，负责水印配置的导入导出功能
 */
class WatermarkConfigManager(private val context: Context) {

    companion object {
        private const val CONFIG_FILE_NAME = "watermark_config.json"
        private const val FONT_DIR_NAME = "fonts"
        private const val IMAGE_DIR_NAME = "images"
    }

    /**
     * 导出水印配置到zip文件
     * @param config 要导出的水印配置
     * @param outputUri 输出文件的Uri
     */
    fun exportConfig(config: WatermarkConfig, outputUri: Uri) {
        context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
            ZipOutputStream(outputStream).use { zipOut ->
                // 写入配置文件
                writeConfigToZip(config, zipOut)

                // 如果有字体文件，添加到zip中
                if (config.fontPath.isNotEmpty() && File(config.fontPath).exists()) {
                    addFileToZip(File(config.fontPath), "$FONT_DIR_NAME/", zipOut)
                }

                // 如果有图片文件，添加到zip中
                if (config.imagePath.isNotEmpty() && File(config.imagePath).exists()) {
                    addFileToZip(File(config.imagePath), "$IMAGE_DIR_NAME/", zipOut)
                }
            }
        } ?: throw IOException("无法创建输出流")
    }

    /**
     * 从zip文件导入水印配置
     * @param inputUri 输入文件的Uri
     * @return 导入的水印配置
     */
    fun importConfig(inputUri: Uri): WatermarkConfig {
        var config: WatermarkConfig? = null
        var fontFileName: String? = null
        var imageFileName: String? = null

        context.contentResolver.openInputStream(inputUri)?.use { inputStream ->
            ZipInputStream(inputStream).use { zipIn ->
                var entry = zipIn.nextEntry

                while (entry != null) {
                    when {
                        entry.name == CONFIG_FILE_NAME -> {
                            // 读取配置文件
                            val configJson = readEntryAsString(zipIn)
                            config = parseConfigFromJson(configJson)
                        }

                        entry.name.startsWith("$FONT_DIR_NAME/") && !entry.isDirectory -> {
                            // 提取字体文件
                            fontFileName = extractFileFromZip(zipIn, entry.name, "fonts")
                        }

                        entry.name.startsWith("$IMAGE_DIR_NAME/") && !entry.isDirectory -> {
                            // 提取图片文件
                            imageFileName = extractFileFromZip(zipIn, entry.name, "watermarks")
                        }
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
        } ?: throw IOException("无法读取输入流")

        // 更新配置中的文件路径
        return config?.let {
            it.copy(
                fontPath = fontFileName ?: "",
                imagePath = imageFileName ?: ""
            )
        } ?: throw IllegalStateException("配置文件损坏或不存在")
    }

    private fun writeConfigToZip(config: WatermarkConfig, zipOut: ZipOutputStream) {
        // 创建配置JSON，但不包含文件路径（因为文件会单独存储）
        val configJson = createConfigJson(config)

        val entry = ZipEntry(CONFIG_FILE_NAME)
        zipOut.putNextEntry(entry)
        zipOut.write(configJson.toByteArray())
        zipOut.closeEntry()
    }

    private fun addFileToZip(file: File, pathPrefix: String, zipOut: ZipOutputStream) {
        val fileName = file.name
        val entry = ZipEntry("$pathPrefix$fileName")
        zipOut.putNextEntry(entry)

        FileInputStream(file).use { fileInput ->
            fileInput.copyTo(zipOut)
        }

        zipOut.closeEntry()
    }

    private fun readEntryAsString(zipIn: ZipInputStream): String {
        return zipIn.readBytes().toString(Charsets.UTF_8)
    }

    private fun extractFileFromZip(
        zipIn: ZipInputStream,
        entryName: String,
        targetDir: String
    ): String {
        val fileName = entryName.substringAfterLast("/")
        val targetDirectory = File(context.filesDir, targetDir)
        if (!targetDirectory.exists()) {
            targetDirectory.mkdirs()
        }

        val targetFile = File(targetDirectory, fileName)
        FileOutputStream(targetFile).use { output ->
            zipIn.copyTo(output)
        }

        return targetFile.absolutePath
    }

    private fun createConfigJson(config: WatermarkConfig): String {
        val json = JSONObject().apply {
            put("isEnabled", config.isEnabled)
            put("enableTextWatermark", config.enableTextWatermark)
            put("enableImageWatermark", config.enableImageWatermark)

            // 新的分离位置参数
            put("textPositionX", config.textPositionX.toDouble())
            put("textPositionY", config.textPositionY.toDouble())
            put("imagePositionX", config.imagePositionX.toDouble())
            put("imagePositionY", config.imagePositionY.toDouble())

            // 新的分离透明度参数
            put("textOpacity", config.textOpacity.toDouble())
            put("imageOpacity", config.imageOpacity.toDouble())

            // 向后兼容参数
            put("positionX", config.textPositionX.toDouble())
            put("positionY", config.textPositionY.toDouble())
            put("opacity", config.textOpacity.toDouble())

            put("textSize", config.textSize.toDouble())
            put("imageSize", config.imageSize.toDouble())
            put("textContent", config.textContent)
            put("textColor", config.textColor)
            put("textAlignment", config.textAlignment.name)
            put("textImageSpacing", config.textImageSpacing.toDouble())
            put("borderTopWidth", config.borderTopWidth.toDouble())
            put("borderBottomWidth", config.borderBottomWidth.toDouble())
            put("borderLeftWidth", config.borderLeftWidth.toDouble())
            put("borderRightWidth", config.borderRightWidth.toDouble())
            put("borderColor", config.borderColor)
            put("letterSpacing", config.letterSpacing.toDouble())
            put("lineSpacing", config.lineSpacing.toDouble())

            // 新增文字跟随模式配置
            put("enableTextFollowMode", config.enableTextFollowMode)
            put("textFollowDirection", config.textFollowDirection.name)
            // 注意：fontPath和imagePath不包含在JSON中，因为文件会单独存储
        }
        return json.toString(2) // 格式化输出
    }

    private fun parseConfigFromJson(jsonString: String): WatermarkConfig {
        val json = JSONObject(jsonString)

        // 读取文本对齐方式
        val textAlignmentName = json.optString("textAlignment", "LEFT")
        val textAlignment = try {
            cn.alittlecookie.lut2photo.lut2photo.model.TextAlignment.valueOf(textAlignmentName)
        } catch (e: IllegalArgumentException) {
            cn.alittlecookie.lut2photo.lut2photo.model.TextAlignment.LEFT
        }

        // 读取文字跟随模式配置
        val textFollowDirectionName = json.optString("textFollowDirection", "BOTTOM")
        val textFollowDirection = try {
            cn.alittlecookie.lut2photo.lut2photo.model.TextFollowDirection.valueOf(
                textFollowDirectionName
            )
        } catch (e: IllegalArgumentException) {
            cn.alittlecookie.lut2photo.lut2photo.model.TextFollowDirection.BOTTOM
        }

        return WatermarkConfig(
            isEnabled = json.optBoolean("isEnabled", false),
            enableTextWatermark = json.optBoolean("enableTextWatermark", false),
            enableImageWatermark = json.optBoolean("enableImageWatermark", false),

            // 新的分离位置参数，如果不存在则使用旧参数作为默认值
            textPositionX = json.optDouble("textPositionX", json.optDouble("positionX", 50.0))
                .toFloat(),
            textPositionY = json.optDouble("textPositionY", json.optDouble("positionY", 90.0))
                .toFloat(),
            imagePositionX = json.optDouble("imagePositionX", json.optDouble("positionX", 50.0))
                .toFloat(),
            imagePositionY = json.optDouble("imagePositionY", json.optDouble("positionY", 10.0))
                .toFloat(),

            // 新的分离透明度参数，如果不存在则使用旧参数作为默认值
            textOpacity = json.optDouble("textOpacity", json.optDouble("opacity", 80.0)).toFloat(),
            imageOpacity = json.optDouble("imageOpacity", json.optDouble("opacity", 80.0))
                .toFloat(),

            textSize = json.optDouble("textSize", 10.0).toFloat(),
            imageSize = json.optDouble("imageSize", 10.0).toFloat(),
            textContent = json.optString(
                "textContent",
                "拍摄参数：ISO {ISO} 光圈 f/{APERTURE} 快门 {SHUTTER}"
            ),
            textColor = json.optString("textColor", "#FFFFFF"),
            fontPath = "", // 将在导入过程中设置
            textAlignment = textAlignment,
            imagePath = "", // 将在导入过程中设置
            enableTextFollowMode = json.optBoolean("enableTextFollowMode", false),
            textFollowDirection = textFollowDirection,
            textImageSpacing = json.optDouble("textImageSpacing", 0.0).toFloat(),
            borderTopWidth = json.optDouble("borderTopWidth", 0.0).toFloat(),
            borderBottomWidth = json.optDouble("borderBottomWidth", 0.0).toFloat(),
            borderLeftWidth = json.optDouble("borderLeftWidth", 0.0).toFloat(),
            borderRightWidth = json.optDouble("borderRightWidth", 0.0).toFloat(),
            borderColor = json.optString("borderColor", "#000000"),
            letterSpacing = json.optDouble("letterSpacing", 0.0).toFloat(),
            lineSpacing = json.optDouble("lineSpacing", 0.0).toFloat()
        )
    }

    /**
     * 验证配置文件是否有效
     */
    fun isValidConfigFile(uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zipIn ->
                    var hasConfigFile = false
                    var entry = zipIn.nextEntry

                    while (entry != null) {
                        if (entry.name == CONFIG_FILE_NAME) {
                            hasConfigFile = true
                            break
                        }
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                    }

                    hasConfigFile
                }
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 获取配置文件信息
     */
    fun getConfigInfo(uri: Uri): String {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zipIn ->
                    val files = mutableListOf<String>()
                    var entry = zipIn.nextEntry

                    while (entry != null) {
                        files.add(entry.name)
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                    }

                    "配置文件包含: ${files.joinToString(", ")}"
                }
            } ?: "无法读取文件信息"
        } catch (e: Exception) {
            "文件格式错误: ${e.message}"
        }
    }
}