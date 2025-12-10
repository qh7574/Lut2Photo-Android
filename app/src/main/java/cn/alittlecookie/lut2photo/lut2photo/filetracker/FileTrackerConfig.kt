package cn.alittlecookie.lut2photo.lut2photo.filetracker

/**
 * FileTracker配置类
 */
data class FileTrackerConfig(
    val targetFolderUri: String,                                    // 目标文件夹URI
    val allowedExtensions: Set<String> = setOf("jpg", "jpeg", "png", "webp"),  // 允许的文件扩展名
    val coldScanTimeoutMs: Long = 30_000L,                          // 冷扫描超时时间
    val maxQueueSize: Int = 1000,                                   // 最大队列长度
    val fullRescanIntervalHours: Int = 24,                          // 强制全量扫描间隔（小时）
    val batchSize: Int = 500,                                       // 批处理大小
    val fileCompleteCheckDelayMs: Long = 200L,                      // 文件完整性检查延迟
    val fileCompleteCheckMaxRetries: Int = 5                        // 文件完整性检查最大重试次数
)
