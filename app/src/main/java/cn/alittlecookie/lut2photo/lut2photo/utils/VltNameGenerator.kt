package cn.alittlecookie.lut2photo.lut2photo.utils

import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType

/**
 * VLT 文件名生成器
 * 根据原始文件名生成符合相机要求的 8 位文件名
 */
object VltNameGenerator {

    private val pinyinFormat = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.UPPERCASE
        toneType = HanyuPinyinToneType.WITHOUT_TONE
        vCharType = HanyuPinyinVCharType.WITH_V
    }

    /**
     * 生成导出文件名（不含扩展名）
     * @param originalName 原始文件名（不含扩展名）
     * @return 8位字符的文件名
     */
    fun generateUploadName(originalName: String): String {
        val processedChars = mutableListOf<Char>()
        
        for (char in originalName) {
            when {
                // 英文字母和数字直接保留
                char.isLetterOrDigit() && char.code < 128 -> {
                    processedChars.add(char.uppercaseChar())
                }
                // 中文字符转拼音首字母
                char.code > 128 -> {
                    val pinyin = chineseToPinyinFirstLetter(char)
                    if (pinyin != null) {
                        processedChars.add(pinyin)
                    }
                }
                // 其他字符忽略
            }
            
            // 如果已经有8位，停止处理
            if (processedChars.size >= 8) {
                break
            }
        }
        
        // 补齐到8位
        while (processedChars.size < 8) {
            processedChars.add('_')
        }
        
        // 截取前8位
        return processedChars.take(8).joinToString("")
    }

    /**
     * 中文字符转拼音首字母
     * @param char 中文字符
     * @return 拼音首字母（大写），失败返回 null
     */
    private fun chineseToPinyinFirstLetter(char: Char): Char? {
        return try {
            val pinyinArray = PinyinHelper.toHanyuPinyinStringArray(char, pinyinFormat)
            if (pinyinArray != null && pinyinArray.isNotEmpty()) {
                pinyinArray[0].firstOrNull()?.uppercaseChar()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 生成完整的上传文件名（含 .vlt 扩展名）
     * @param originalName 原始文件名（不含扩展名）
     * @return 完整的上传文件名
     */
    fun generateFullUploadName(originalName: String): String {
        return "${generateUploadName(originalName)}.vlt"
    }
}
