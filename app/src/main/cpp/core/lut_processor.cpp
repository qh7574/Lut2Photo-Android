#include "lut_processor.h"
#include <android/log.h>
#include <fstream>
#include <sstream>
#include <algorithm>
#include <cmath>
#include <cctype>

ProcessResult LutProcessor::loadLutFromFile(const std::string &lutPath, LutData &lutData) {
    LOGD("开始从文件加载LUT: %s", lutPath.c_str());

    std::ifstream file(lutPath, std::ios::binary);
    if (!file.is_open()) {
        LOGE("无法打开LUT文件: %s", lutPath.c_str());
        return ProcessResult::ERROR_PROCESSING_FAILED;
    }

    // 读取文件内容
    file.seekg(0, std::ios::end);
    size_t fileSize = file.tellg();
    file.seekg(0, std::ios::beg);

    std::vector<uint8_t> buffer(fileSize);
    file.read(reinterpret_cast<char *>(buffer.data()), fileSize);
    file.close();

    return loadLutFromMemory(buffer.data(), fileSize, lutData);
}

ProcessResult
LutProcessor::loadLutFromMemory(const uint8_t *lutBytes, size_t size, LutData &lutData) {
    if (!lutBytes || size == 0) {
        LOGE("LUT数据为空");
        return ProcessResult::ERROR_INVALID_PARAMETERS;
    }

    // 转换为字符串
    std::string content(reinterpret_cast<const char *>(lutBytes), size);

    // 检测文件格式
    std::string lowerContent = toLowerCase(content);
    bool isCube = lowerContent.find("lut_3d_size") != std::string::npos;
    bool is3dl = lowerContent.find("3dl") != std::string::npos ||
                 content.find("\t") != std::string::npos;

    bool success = false;
    if (isCube) {
        LOGD("检测到.cube格式LUT");
        success = parseCubeLut(content, lutData);
    } else if (is3dl) {
        LOGD("检测到.3dl格式LUT");
        success = parse3dlLut(content, lutData);
    } else {
        LOGD("尝试解析为.cube格式");
        success = parseCubeLut(content, lutData);
        if (!success) {
            LOGD("尝试解析为.3dl格式");
            success = parse3dlLut(content, lutData);
        }
    }

    if (success) {
        lutData.isLoaded = true;
        LOGD("LUT加载成功，尺寸: %d", lutData.size);
        return ProcessResult::SUCCESS;
    } else {
        LOGE("LUT解析失败");
        return ProcessResult::ERROR_LUT_NOT_LOADED;
    }
}

void LutProcessor::applyLut(
        float r, float g, float b,
        float &outR, float &outG, float &outB,
        const LutData &lutData
) {
    if (!lutData.isLoaded || lutData.data.empty()) {
        outR = r;
        outG = g;
        outB = b;
        return;
    }

    // 将输入值映射到LUT索引空间
    float x = std::clamp(r, 0.0f, 1.0f) * (lutData.size - 1);
    float y = std::clamp(g, 0.0f, 1.0f) * (lutData.size - 1);
    float z = std::clamp(b, 0.0f, 1.0f) * (lutData.size - 1);

    // 使用三线性插值
    trilinearInterpolation(x / (lutData.size - 1), y / (lutData.size - 1), z / (lutData.size - 1),
                           lutData, outR, outG, outB);
}

void LutProcessor::releaseLutData(LutData &lutData) {
    lutData.clear();
    LOGD("LUT数据已释放");
}

bool LutProcessor::isValidLutData(const LutData &lutData) {
    return lutData.isLoaded && !lutData.data.empty() && lutData.size > 0;
}

std::string LutProcessor::getLutInfo(const LutData &lutData) {
    if (!isValidLutData(lutData)) {
        return "无效的LUT数据";
    }

    std::ostringstream oss;
    oss << "LUT尺寸: " << lutData.size << "x" << lutData.size << "x" << lutData.size;
    oss << ", 总数据点: " << (lutData.size * lutData.size * lutData.size);
    oss << ", 内存使用: " << (lutData.size * lutData.size * lutData.size * 3 * sizeof(float) / 1024)
        << "KB";

    return oss.str();
}

bool LutProcessor::parseCubeLut(const std::string &content, LutData &lutData) {
    std::istringstream iss(content);
    std::string line;
    std::vector<std::string> lines;

    // 读取所有行
    while (std::getline(iss, line)) {
        lines.push_back(trim(line));
    }

    if (lines.empty()) {
        LOGE(".cube文件为空");
        return false;
    }

    size_t lineIndex = 0;
    lutData.size = 0;

    // 解析头部信息
    while (lineIndex < lines.size()) {
        lineIndex = skipEmptyAndComments(lines, lineIndex);
        if (lineIndex >= lines.size()) break;

        std::string currentLine = toLowerCase(lines[lineIndex]);

        if (currentLine.find("lut_3d_size") == 0) {
            std::vector<std::string> parts = split(lines[lineIndex], ' ');
            if (parts.size() >= 2) {
                try {
                    lutData.size = std::stoi(parts[1]);
                    LOGD("解析到LUT尺寸: %d", lutData.size);
                } catch (const std::exception &e) {
                    LOGE("解析LUT尺寸失败: %s", e.what());
                    return false;
                }
            }
            lineIndex++;
            break;
        }
        lineIndex++;
    }

    if (lutData.size <= 0 || lutData.size > 256) {
        LOGE("无效的LUT尺寸: %d", lutData.size);
        return false;
    }

    // 分配内存
    const int totalEntries = lutData.size * lutData.size * lutData.size;
    lutData.data.resize(totalEntries * 3);

    // 解析LUT数据
    int dataIndex = 0;
    while (lineIndex < lines.size() && dataIndex < totalEntries) {
        lineIndex = skipEmptyAndComments(lines, lineIndex);
        if (lineIndex >= lines.size()) break;

        std::vector<std::string> values = split(lines[lineIndex], ' ');
        if (values.size() >= 3) {
            try {
                lutData.data[dataIndex * 3 + 0] = std::stof(values[0]); // R
                lutData.data[dataIndex * 3 + 1] = std::stof(values[1]); // G
                lutData.data[dataIndex * 3 + 2] = std::stof(values[2]); // B
                dataIndex++;
            } catch (const std::exception &e) {
                LOGE("解析LUT数据失败，行 %zu: %s", lineIndex, e.what());
                lutData.clear();
                return false;
            }
        }
        lineIndex++;
    }

    if (dataIndex != totalEntries) {
        LOGE("LUT数据不完整，期望 %d 个条目，实际 %d 个", totalEntries, dataIndex);
        lutData.clear();
        return false;
    }

    LOGD(".cube LUT解析成功");
    return true;
}

bool LutProcessor::parse3dlLut(const std::string &content, LutData &lutData) {
    std::istringstream iss(content);
    std::string line;
    std::vector<std::vector<float>> dataLines;

    // 读取所有数据行
    while (std::getline(iss, line)) {
        line = trim(line);
        if (line.empty() || line[0] == '#') continue;

        std::vector<std::string> values = split(line, '\t');
        if (values.empty()) {
            values = split(line, ' ');
        }

        if (values.size() >= 3) {
            std::vector<float> rgb;
            try {
                for (int i = 0; i < 3; ++i) {
                    float value = std::stof(values[i]);
                    // 3dl格式通常使用0-1023或0-4095范围，需要归一化
                    if (value > 1.0f) {
                        if (value <= 1023.0f) {
                            value /= 1023.0f;
                        } else if (value <= 4095.0f) {
                            value /= 4095.0f;
                        } else {
                            value /= 65535.0f;
                        }
                    }
                    rgb.push_back(value);
                }
                dataLines.push_back(rgb);
            } catch (const std::exception &e) {
                LOGE("解析3dl数据失败: %s", e.what());
                continue;
            }
        }
    }

    if (dataLines.empty()) {
        LOGE("3dl文件没有有效数据");
        return false;
    }

    // 推断LUT尺寸
    int totalEntries = dataLines.size();
    lutData.size = static_cast<int>(std::round(std::cbrt(totalEntries)));

    if (lutData.size * lutData.size * lutData.size != totalEntries) {
        LOGE("3dl数据大小不是完美立方体: %d", totalEntries);
        return false;
    }

    LOGD("推断3dl LUT尺寸: %d", lutData.size);

    // 分配内存并复制数据
    lutData.data.resize(totalEntries * 3);

    for (int i = 0; i < totalEntries; ++i) {
        lutData.data[i * 3 + 0] = dataLines[i][0]; // R
        lutData.data[i * 3 + 1] = dataLines[i][1]; // G
        lutData.data[i * 3 + 2] = dataLines[i][2]; // B
    }

    LOGD("3dl LUT解析成功");
    return true;
}

void LutProcessor::trilinearInterpolation(
        float x, float y, float z,
        const LutData &lutData,
        float &outR, float &outG, float &outB
) {
    // 将坐标映射到LUT索引空间
    float fx = x * (lutData.size - 1);
    float fy = y * (lutData.size - 1);
    float fz = z * (lutData.size - 1);

    // 获取整数部分和小数部分
    int x0 = static_cast<int>(fx);
    int y0 = static_cast<int>(fy);
    int z0 = static_cast<int>(fz);

    int x1 = std::min(x0 + 1, lutData.size - 1);
    int y1 = std::min(y0 + 1, lutData.size - 1);
    int z1 = std::min(z0 + 1, lutData.size - 1);

    float dx = fx - x0;
    float dy = fy - y0;
    float dz = fz - z0;

    // 获取8个顶点的值
    float c000[3], c001[3], c010[3], c011[3];
    float c100[3], c101[3], c110[3], c111[3];

    getLutValue(x0, y0, z0, lutData, c000[0], c000[1], c000[2]);
    getLutValue(x0, y0, z1, lutData, c001[0], c001[1], c001[2]);
    getLutValue(x0, y1, z0, lutData, c010[0], c010[1], c010[2]);
    getLutValue(x0, y1, z1, lutData, c011[0], c011[1], c011[2]);
    getLutValue(x1, y0, z0, lutData, c100[0], c100[1], c100[2]);
    getLutValue(x1, y0, z1, lutData, c101[0], c101[1], c101[2]);
    getLutValue(x1, y1, z0, lutData, c110[0], c110[1], c110[2]);
    getLutValue(x1, y1, z1, lutData, c111[0], c111[1], c111[2]);

    // 三线性插值
    for (int i = 0; i < 3; ++i) {
        float c00 = c000[i] * (1 - dx) + c100[i] * dx;
        float c01 = c001[i] * (1 - dx) + c101[i] * dx;
        float c10 = c010[i] * (1 - dx) + c110[i] * dx;
        float c11 = c011[i] * (1 - dx) + c111[i] * dx;

        float c0 = c00 * (1 - dy) + c10 * dy;
        float c1 = c01 * (1 - dy) + c11 * dy;

        float result = c0 * (1 - dz) + c1 * dz;

        if (i == 0) outR = result;
        else if (i == 1) outG = result;
        else outB = result;
    }
}

void LutProcessor::getLutValue(
        int r, int g, int b,
        const LutData &lutData,
        float &outR, float &outG, float &outB
) {
    // 确保索引在有效范围内
    r = std::clamp(r, 0, lutData.size - 1);
    g = std::clamp(g, 0, lutData.size - 1);
    b = std::clamp(b, 0, lutData.size - 1);

    // 计算线性索引
    int index = r * lutData.size * lutData.size + g * lutData.size + b;

    outR = lutData.data[index * 3 + 0];
    outG = lutData.data[index * 3 + 1];
    outB = lutData.data[index * 3 + 2];
}

size_t LutProcessor::skipEmptyAndComments(const std::vector<std::string> &lines, size_t index) {
    while (index < lines.size()) {
        std::string line = trim(lines[index]);
        if (!line.empty() && line[0] != '#') {
            break;
        }
        index++;
    }
    return index;
}

std::vector<std::string> LutProcessor::split(const std::string &str, char delimiter) {
    std::vector<std::string> tokens;
    std::istringstream iss(str);
    std::string token;

    while (std::getline(iss, token, delimiter)) {
        token = trim(token);
        if (!token.empty()) {
            tokens.push_back(token);
        }
    }

    return tokens;
}

std::string LutProcessor::trim(const std::string &str) {
    size_t start = str.find_first_not_of(" \t\r\n");
    if (start == std::string::npos) {
        return "";
    }

    size_t end = str.find_last_not_of(" \t\r\n");
    return str.substr(start, end - start + 1);
}

std::string LutProcessor::toLowerCase(const std::string &str) {
    std::string result = str;
    std::transform(result.begin(), result.end(), result.begin(), ::tolower);
    return result;
}