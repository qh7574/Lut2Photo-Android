package cn.alittlecookie.lut2photo.lut2photo.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 内置LUT文件初始化器
 * 负责在应用首次启动时将assets/luts中的内置LUT文件复制到应用内部存储
 */
class BuiltInLutInitializer(private val context: Context) {
    
    companion object {
        private const val TAG = "BuiltInLutInitializer"
        private const val PREF_NAME = "lut_initializer_prefs"
        private const val KEY_INITIALIZED = "built_in_luts_initialized"
        private const val KEY_LUT_VERSION = "built_in_luts_version"
        
        // LUT文件版本号 - 每次修改内置LUT列表时需要递增此版本号
        private const val CURRENT_LUT_VERSION = 5
        
        // 内置LUT文件列表
        private val BUILT_IN_LUTS = listOf(
            "CyanOrganic.cube",
            "Fuji-ACROS.cube",
            "Fuji-ASTIA.cube",
            "Fuji-ClassicChrome.cube",
            "Fuji-ClassicNegative.cube",
            "Fuji-Eterna.cube",
            "Fuji-EternaBleachBypass.cube",
            "Fuji-ProNegStd.cube",
            "Fuji-PROVIA.cube",
            "Fuji-REALA.cube",
            "Fuji-VELVIA.cube",
            "GR-BleachBypass_V4.cube",
            "GR-Negative_V4.cube",
            "GR-Positive_V4.cube",
            "GR-RetroBlue_V4.cube",
            "JC-Negative_V4.cube",
            "JC-Positive_V4.cube",
            "GR-一键森山大道_V4.cube",
            "sRGBto709.cube",
            "VLogto709.cube"
        )

        // 旧版本内置LUT文件列表 - 版本更新时需要删除的文件
        // 请在此处添加需要删除的旧版本LUT文件名
        private val OLD_VERSION_LUTS = listOf<String>(
            "ACROS.cube",
            "ASTIA.cube",
            "ClassicChrome.cube",
            "ClassicNegative.cube",
            "Eterna.cube",
            "EternaBleachBypass.cube",
            "ProNegStd.cube",
            "PROVIA.cube",
            "REALA.cube",
            "VELVIA.cube"// 示例: "OldLut1.cube", "OldLut2.cube"
        )
    }
    
    private val prefs by lazy {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }
    
    private val lutDirectory: File by lazy {
        File(context.getExternalFilesDir(null), "android_data/luts")
    }
    
    /**
     * 检查是否已经初始化过内置LUT文件
     * 如果版本号不匹配，则需要重新初始化
     */
    fun isInitialized(): Boolean {
        val initialized = prefs.getBoolean(KEY_INITIALIZED, false)
        val savedVersion = prefs.getInt(KEY_LUT_VERSION, 0)
        
        // 如果版本号不匹配，需要重新初始化
        if (initialized && savedVersion != CURRENT_LUT_VERSION) {
            Log.i(TAG, "检测到LUT版本更新: $savedVersion -> $CURRENT_LUT_VERSION，需要重新释放文件")
            return false
        }
        
        return initialized
    }
    
    /**
     * 初始化内置LUT文件
     * 将assets/luts中的文件复制到应用内部存储
     */
    suspend fun initializeBuiltInLuts(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "开始初始化内置LUT文件")
            
            // 确保目标目录存在
            if (!lutDirectory.exists()) {
                val created = lutDirectory.mkdirs()
                Log.d(TAG, "创建LUT目录: ${lutDirectory.absolutePath}, 结果: $created")
                if (!created) {
                    Log.e(TAG, "无法创建LUT目录")
                    return@withContext false
                }
            }

            // 删除旧版本LUT文件
            deleteOldVersionLuts()
            
            var successCount = 0
            var failCount = 0
            
            // 复制每个内置LUT文件
            BUILT_IN_LUTS.forEach { fileName ->
                try {
                    val targetFile = File(lutDirectory, fileName)
                    
                    // 从assets/luts读取文件
                    val assetFileName = fileName
                    
                    // 如果文件已存在，覆盖它以确保使用最新版本
                    if (targetFile.exists()) {
                        Log.d(TAG, "文件已存在，将覆盖: $fileName")
                    }
                    
                    // 复制文件（覆盖模式）
                    context.assets.open("luts/$assetFileName").use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    
                    Log.d(TAG, "成功复制文件: $fileName (${targetFile.length()} bytes)")
                    successCount++
                    
                } catch (e: Exception) {
                    Log.e(TAG, "复制文件失败: $fileName", e)
                    failCount++
                }
            }
            
            Log.i(TAG, "内置LUT文件初始化完成: 成功 $successCount, 失败 $failCount")
            
            // 只有全部成功才标记为已初始化
            val allSuccess = failCount == 0
            if (allSuccess) {
                prefs.edit()
                    .putBoolean(KEY_INITIALIZED, true)
                    .putInt(KEY_LUT_VERSION, CURRENT_LUT_VERSION)
                    .apply()
                Log.i(TAG, "标记内置LUT文件已初始化，版本: $CURRENT_LUT_VERSION")
            }
            
            allSuccess
            
        } catch (e: Exception) {
            Log.e(TAG, "初始化内置LUT文件失败", e)
            false
        }
    }
    
    /**
     * 重置初始化状态（用于测试或重新初始化）
     */
    fun resetInitializationState() {
        prefs.edit().putBoolean(KEY_INITIALIZED, false).apply()
        Log.i(TAG, "重置初始化状态")
    }

    /**
     * 删除旧版本LUT文件
     * 根据OLD_VERSION_LUTS列表删除指定的旧版本文件
     */
    private fun deleteOldVersionLuts() {
        if (OLD_VERSION_LUTS.isEmpty()) {
            Log.d(TAG, "没有需要删除的旧版本LUT文件")
            return
        }

        Log.i(TAG, "开始删除旧版本LUT文件，共 ${OLD_VERSION_LUTS.size} 个")
        var deleteCount = 0
        var notFoundCount = 0

        OLD_VERSION_LUTS.forEach { fileName ->
            try {
                val file = File(lutDirectory, fileName)
                if (file.exists()) {
                    val deleted = file.delete()
                    if (deleted) {
                        Log.d(TAG, "成功删除旧版本LUT文件: $fileName")
                        deleteCount++
                    } else {
                        Log.w(TAG, "删除旧版本LUT文件失败: $fileName")
                    }
                } else {
                    Log.d(TAG, "旧版本LUT文件不存在，跳过: $fileName")
                    notFoundCount++
                }
            } catch (e: Exception) {
                Log.e(TAG, "删除旧版本LUT文件异常: $fileName", e)
            }
        }

        Log.i(TAG, "旧版本LUT文件删除完成: 成功删除 $deleteCount 个, 未找到 $notFoundCount 个")
    }
}
