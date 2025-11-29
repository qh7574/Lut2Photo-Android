package cn.alittlecookie.lut2photo.lut2photo.utils

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * VLT 文件管理器
 * 负责管理 .vlt 文件的存储和 .cube 文件的映射关系
 */
class VltFileManager(private val context: Context) {

    companion object {
        private const val TAG = "VltFileManager"
        private const val VLT_FOLDER = "vlts"
        private const val MAPPING_FILE = "mapping.json"
    }

    private val vltsDir: File by lazy {
        File(context.filesDir, VLT_FOLDER).apply {
            if (!exists()) {
                mkdirs()
                Log.d(TAG, "创建 vlts 文件夹: $absolutePath")
            }
        }
    }

    private val mappingFile: File by lazy {
        File(vltsDir, MAPPING_FILE)
    }

    /**
     * 保存 VLT 文件到内部存储
     * @param sourceFile 源 VLT 文件
     * @return 保存后的文件，失败返回 null
     */
    fun saveVltFile(sourceFile: File): File? {
        return try {
            val destFile = File(vltsDir, sourceFile.name)
            sourceFile.copyTo(destFile, overwrite = true)
            Log.i(TAG, "VLT 文件已保存: ${destFile.absolutePath}")
            destFile
        } catch (e: Exception) {
            Log.e(TAG, "保存 VLT 文件失败", e)
            null
        }
    }

    /**
     * 从输入流保存 VLT 文件到内部存储
     * @param inputStream 输入流
     * @param fileName 文件名
     * @return 保存后的文件，失败返回 null
     */
    fun saveVltFileFromStream(inputStream: java.io.InputStream, fileName: String): File? {
        return try {
            val destFile = File(vltsDir, fileName)
            destFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }
            Log.i(TAG, "VLT 文件已从流保存: ${destFile.absolutePath}")
            destFile
        } catch (e: Exception) {
            Log.e(TAG, "从流保存 VLT 文件失败", e)
            null
        }
    }

    /**
     * 添加 CUBE 和 VLT 的映射关系
     * @param cubeName CUBE 文件名（如 "lut.cube"）
     * @param vltName VLT 文件名（如 "PROVIA.vlt"）
     */
    fun addMapping(cubeName: String, vltName: String) {
        try {
            val mappings = loadMappings().toMutableMap()
            mappings[cubeName] = vltName
            saveMappings(mappings)
            Log.d(TAG, "添加映射: $cubeName -> $vltName")
        } catch (e: Exception) {
            Log.e(TAG, "添加映射失败", e)
        }
    }

    /**
     * 获取 CUBE 文件对应的 VLT 文件名
     * @param cubeName CUBE 文件名
     * @return VLT 文件名，不存在返回 null
     */
    fun getVltName(cubeName: String): String? {
        val mappings = loadMappings()
        Log.d(TAG, "查询映射: $cubeName")
        Log.d(TAG, "  - 映射文件路径: ${mappingFile.absolutePath}")
        Log.d(TAG, "  - 映射文件存在: ${mappingFile.exists()}")
        Log.d(TAG, "  - 当前所有映射: $mappings")
        val result = mappings[cubeName]
        Log.d(TAG, "  - 查询结果: $result")
        return result
    }

    /**
     * 获取 VLT 文件
     * @param vltName VLT 文件名
     * @return VLT 文件，不存在返回 null
     */
    fun getVltFile(vltName: String): File? {
        val file = File(vltsDir, vltName)
        return if (file.exists()) file else null
    }

    /**
     * 检查 CUBE 文件是否有对应的 VLT 文件
     * @param cubeName CUBE 文件名
     * @return true 表示存在
     */
    fun hasVltFile(cubeName: String): Boolean {
        val vltName = getVltName(cubeName) ?: return false
        return getVltFile(vltName)?.exists() == true
    }

    /**
     * 删除映射关系
     * @param cubeName CUBE 文件名
     */
    fun removeMapping(cubeName: String) {
        try {
            val mappings = loadMappings().toMutableMap()
            mappings.remove(cubeName)
            saveMappings(mappings)
            Log.d(TAG, "删除映射: $cubeName")
        } catch (e: Exception) {
            Log.e(TAG, "删除映射失败", e)
        }
    }

    /**
     * 加载映射关系
     */
    private fun loadMappings(): Map<String, String> {
        return try {
            Log.d(TAG, "加载映射文件: ${mappingFile.absolutePath}")
            if (!mappingFile.exists()) {
                Log.d(TAG, "映射文件不存在，返回空映射")
                return emptyMap()
            }
            val content = mappingFile.readText()
            Log.d(TAG, "映射文件内容: $content")
            val json = JSONObject(content)
            val map = mutableMapOf<String, String>()
            json.keys().forEach { key ->
                map[key] = json.getString(key)
            }
            Log.d(TAG, "加载了 ${map.size} 个映射关系")
            map
        } catch (e: Exception) {
            Log.e(TAG, "加载映射失败", e)
            emptyMap()
        }
    }

    /**
     * 保存映射关系
     */
    private fun saveMappings(mappings: Map<String, String>) {
        try {
            val json = JSONObject()
            mappings.forEach { (key, value) ->
                json.put(key, value)
            }
            mappingFile.writeText(json.toString(2))
            Log.d(TAG, "映射已保存")
        } catch (e: Exception) {
            Log.e(TAG, "保存映射失败", e)
        }
    }
}
