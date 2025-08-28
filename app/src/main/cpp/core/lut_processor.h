#ifndef LUT_PROCESSOR_H
#define LUT_PROCESSOR_H

#include "../include/native_lut_processor.h"
#include <string>
#include <vector>

/**
 * LUT处理器类
 * 负责LUT文件的加载、解析和应用
 */
class LutProcessor {
public:
    /**
     * 从文件加载LUT
     * @param lutPath LUT文件路径
     * @param lutData 输出的LUT数据
     * @return 处理结果
     */
    static ProcessResult loadLutFromFile(const std::string &lutPath, LutData &lutData);

    /**
     * 从内存数据加载LUT
     * @param lutBytes LUT文件字节数据
     * @param size 数据大小
     * @param lutData 输出的LUT数据
     * @return 处理结果
     */
    static ProcessResult loadLutFromMemory(const uint8_t *lutBytes, size_t size, LutData &lutData);

    /**
     * 应用LUT到RGB值
     * @param r 输入红色值 [0,1]
     * @param g 输入绿色值 [0,1]
     * @param b 输入蓝色值 [0,1]
     * @param outR 输出红色值
     * @param outG 输出绿色值
     * @param outB 输出蓝色值
     * @param lutData LUT数据
     */
    static void applyLut(
            float r, float g, float b,
            float &outR, float &outG, float &outB,
            const LutData &lutData
    );

    /**
     * 释放LUT数据
     * @param lutData 要释放的LUT数据
     */
    static void releaseLutData(LutData &lutData);

    /**
     * 验证LUT数据有效性
     * @param lutData LUT数据
     * @return 是否有效
     */
    static bool isValidLutData(const LutData &lutData);

    /**
     * 获取LUT信息字符串
     * @param lutData LUT数据
     * @return 信息字符串
     */
    static std::string getLutInfo(const LutData &lutData);

private:
    /**
     * 解析.cube格式LUT文件
     * @param content 文件内容
     * @param lutData 输出的LUT数据
     * @return 是否成功
     */
    static bool parseCubeLut(const std::string &content, LutData &lutData);

    /**
     * 解析.3dl格式LUT文件
     * @param content 文件内容
     * @param lutData 输出的LUT数据
     * @return 是否成功
     */
    static bool parse3dlLut(const std::string &content, LutData &lutData);

    /**
     * 三线性插值
     * @param x, y, z 插值坐标 [0,1]
     * @param lutData LUT数据
     * @param outR, outG, outB 输出RGB值
     */
    static void trilinearInterpolation(
            float x, float y, float z,
            const LutData &lutData,
            float &outR, float &outG, float &outB
    );

    /**
     * 获取LUT中指定位置的RGB值
     * @param r, g, b 索引位置
     * @param lutData LUT数据
     * @param outR, outG, outB 输出RGB值
     */
    static void getLutValue(
            int r, int g, int b,
            const LutData &lutData,
            float &outR, float &outG, float &outB
    );

    /**
     * 跳过空白行和注释
     * @param lines 文件行数组
     * @param index 当前行索引
     * @return 下一个有效行索引
     */
    static size_t skipEmptyAndComments(const std::vector<std::string> &lines, size_t index);

    /**
     * 分割字符串
     * @param str 输入字符串
     * @param delimiter 分隔符
     * @return 分割后的字符串数组
     */
    static std::vector<std::string> split(const std::string &str, char delimiter);

    /**
     * 去除字符串首尾空白
     * @param str 输入字符串
     * @return 处理后的字符串
     */
    static std::string trim(const std::string &str);

    /**
     * 字符串转小写
     * @param str 输入字符串
     * @return 小写字符串
     */
    static std::string toLowerCase(const std::string &str);
};

#endif // LUT_PROCESSOR_H