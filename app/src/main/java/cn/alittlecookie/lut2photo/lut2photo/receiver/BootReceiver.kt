package cn.alittlecookie.lut2photo.lut2photo.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import cn.alittlecookie.lut2photo.lut2photo.model.LutItem
import cn.alittlecookie.lut2photo.lut2photo.service.FolderMonitorService
import cn.alittlecookie.lut2photo.lut2photo.utils.LutManager
import cn.alittlecookie.lut2photo.lut2photo.utils.PreferencesManager

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_REPLACED -> {
                Log.d(TAG, "系统启动或应用更新，检查是否需要恢复监控服务")

                val preferencesManager = PreferencesManager(context)

                // 修复：使用monitoringSwitchEnabled而不是isMonitoring来判断
                // monitoringSwitchEnabled表示用户的意图，isMonitoring只是服务的运行状态
                if (preferencesManager.monitoringSwitchEnabled &&
                    preferencesManager.homeInputFolder.isNotEmpty() &&
                    preferencesManager.homeOutputFolder.isNotEmpty() &&
                    preferencesManager.homeLutUri?.isNotEmpty() == true
                ) {

                    Log.d(TAG, "恢复文件夹监控服务（用户之前启用了监控）")

                    val serviceIntent = Intent(context, FolderMonitorService::class.java).apply {
                        action = FolderMonitorService.ACTION_START_MONITORING
                        putExtra(
                            FolderMonitorService.EXTRA_INPUT_FOLDER,
                            preferencesManager.homeInputFolder
                        )
                        putExtra(
                            FolderMonitorService.EXTRA_OUTPUT_FOLDER,
                            preferencesManager.homeOutputFolder
                        )
                        // 修复：LUT路径需要从文件名转换为完整路径
                        preferencesManager.homeLutUri?.let { lutFileName ->
                            val lutManager = LutManager(context)
                            // 创建临时LutItem来获取完整路径
                            val tempLutItem = LutItem(
                                id = lutFileName,
                                name = lutFileName,
                                filePath = lutFileName,
                                size = 0,
                                lastModified = 0
                            )
                            putExtra(
                                FolderMonitorService.EXTRA_LUT_FILE_PATH,
                                lutManager.getLutFilePath(tempLutItem)
                            )
                        }
                        // 修复：转换为Integer类型
                        putExtra(
                            FolderMonitorService.EXTRA_STRENGTH,
                            preferencesManager.homeStrength.toInt()
                        )
                        putExtra(
                            FolderMonitorService.EXTRA_QUALITY,
                            preferencesManager.homeQuality.toInt()
                        )
                        putExtra(
                            FolderMonitorService.EXTRA_DITHER,
                            preferencesManager.homeDitherType
                        )
                    }

                    try {
                        context.startForegroundService(serviceIntent)
                    } catch (e: Exception) {
                        Log.e(TAG, "启动监控服务失败", e)
                    }
                }
            }
        }
    }
}