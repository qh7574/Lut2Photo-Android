#include "exception_handler.h"
#include <android/log.h>
#include <algorithm>
#include <sstream>
#include <iomanip>
#include <cstring>

#ifndef LOG_TAG
#define LOG_TAG "ExceptionHandler"
#endif
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

ExceptionHandler &ExceptionHandler::getInstance() {
    static ExceptionHandler instance;
    return instance;
}

void ExceptionHandler::handleException(const ExceptionInfo &info) {
    std::lock_guard<std::mutex> lock(mutex_);

    // 记录异常
    logException(info);

    // 更新统计
    totalExceptions_++;
    exceptionCounts_[info.type]++;

    // 添加到最近异常列表
    recentExceptions_.push_back(info);
    if (recentExceptions_.size() > MAX_RECENT_EXCEPTIONS) {
        recentExceptions_.erase(recentExceptions_.begin());
    }

    // 更新健康指标
    updateHealthMetrics(info);

    // 检查阈值
    if (isExceptionThresholdExceeded(info.type)) {
        LOGE("异常类型 %d 超过阈值，触发降级策略", static_cast<int>(info.type));

        // 选择并执行降级策略
        FallbackStrategy strategy = selectFallbackStrategy(info);
        executeFallback(strategy, info);
    }

    // 调用用户回调
    if (exceptionCallback_) {
        try {
            exceptionCallback_(info);
        } catch (const std::exception &e) {
            LOGE("异常回调执行失败: %s", e.what());
        }
    }

    // 严重异常处理
    if (info.severity == ExceptionSeverity::CRITICAL) {
        LOGE("检测到致命异常，启动优雅关闭");
        initiateGracefulShutdown("Critical exception: " + info.message);
    }
}

void ExceptionHandler::handleException(ExceptionType type, ExceptionSeverity severity,
                                       const std::string &message, const std::string &location) {
    ExceptionInfo info(type, severity, message, location);
    handleException(info);
}

void ExceptionHandler::setExceptionCallback(ExceptionCallback callback) {
    std::lock_guard<std::mutex> lock(mutex_);
    exceptionCallback_ = std::move(callback);
}

void ExceptionHandler::setFallbackCallback(FallbackCallback callback) {
    std::lock_guard<std::mutex> lock(mutex_);
    fallbackCallback_ = std::move(callback);
}

void ExceptionHandler::removeCallbacks() {
    std::lock_guard<std::mutex> lock(mutex_);
    exceptionCallback_ = nullptr;
    fallbackCallback_ = nullptr;
}

bool ExceptionHandler::executeFallback(FallbackStrategy strategy, const ExceptionInfo &info) {
    LOGI("执行降级策略: %d, 异常类型: %d", static_cast<int>(strategy), static_cast<int>(info.type));

    // 调用用户降级回调
    if (fallbackCallback_) {
        try {
            return fallbackCallback_(strategy, info);
        } catch (const std::exception &e) {
            LOGE("降级回调执行失败: %s", e.what());
        }
    }

    // 默认降级处理
    switch (strategy) {
        case FallbackStrategy::RETRY:
            LOGI("执行重试策略");
            return true;

        case FallbackStrategy::REDUCE_QUALITY:
            LOGI("执行质量降级策略");
            return true;

        case FallbackStrategy::USE_CPU_FALLBACK:
            LOGI("执行CPU回退策略");
            return true;

        case FallbackStrategy::SPLIT_PROCESSING:
            LOGI("执行分块处理策略");
            return true;

        case FallbackStrategy::SKIP_OPERATION:
            LOGW("跳过操作");
            return false;

        case FallbackStrategy::TERMINATE_GRACEFULLY:
            LOGE("优雅终止");
            initiateGracefulShutdown("Fallback strategy: graceful termination");
            return false;

        default:
            LOGE("未知降级策略: %d", static_cast<int>(strategy));
            return false;
    }
}

FallbackStrategy ExceptionHandler::selectFallbackStrategy(const ExceptionInfo &info) {
    switch (info.type) {
        case ExceptionType::MEMORY_ALLOCATION_FAILED:
        case ExceptionType::OUT_OF_MEMORY:
            if (info.severity == ExceptionSeverity::CRITICAL) {
                return FallbackStrategy::TERMINATE_GRACEFULLY;
            } else if (info.severity == ExceptionSeverity::HIGH) {
                return FallbackStrategy::SPLIT_PROCESSING;
            } else {
                return FallbackStrategy::REDUCE_QUALITY;
            }

        case ExceptionType::MEMORY_LIMIT_EXCEEDED:
            return FallbackStrategy::USE_CPU_FALLBACK;

        case ExceptionType::MEMORY_CORRUPTION:
        case ExceptionType::BUFFER_OVERFLOW:
            return FallbackStrategy::TERMINATE_GRACEFULLY;

        case ExceptionType::PROCESSING_ERROR:
            if (info.severity >= ExceptionSeverity::HIGH) {
                return FallbackStrategy::SKIP_OPERATION;
            } else {
                return FallbackStrategy::RETRY;
            }

        case ExceptionType::SYSTEM_ERROR:
            return FallbackStrategy::USE_CPU_FALLBACK;

        default:
            return FallbackStrategy::RETRY;
    }
}

size_t ExceptionHandler::getExceptionCount(ExceptionType type) const {
    std::lock_guard<std::mutex> lock(mutex_);

    if (type == ExceptionType::UNKNOWN_ERROR) {
        return totalExceptions_.load();
    }

    auto it = exceptionCounts_.find(type);
    return (it != exceptionCounts_.end()) ? it->second.load() : 0;
}

size_t ExceptionHandler::getTotalExceptions() const {
    return totalExceptions_.load();
}

std::vector<ExceptionInfo> ExceptionHandler::getRecentExceptions(size_t count) const {
    std::lock_guard<std::mutex> lock(mutex_);

    if (count >= recentExceptions_.size()) {
        return recentExceptions_;
    }

    return std::vector<ExceptionInfo>(recentExceptions_.end() - count, recentExceptions_.end());
}

void ExceptionHandler::setExceptionThreshold(ExceptionType type, size_t threshold,
                                             std::chrono::seconds timeWindow) {
    std::lock_guard<std::mutex> lock(mutex_);

    ThresholdInfo &info = thresholds_[type];
    info.threshold = threshold;
    info.timeWindow = timeWindow;
    info.occurrences.clear();

    LOGI("设置异常阈值: 类型=%d, 阈值=%zu, 时间窗口=%lld秒",
         static_cast<int>(type), threshold, static_cast<long long>(timeWindow.count()));
}

bool ExceptionHandler::isExceptionThresholdExceeded(ExceptionType type) const {
    std::lock_guard<std::mutex> lock(mutex_);

    auto it = thresholds_.find(type);
    if (it == thresholds_.end()) {
        return false;
    }

    const ThresholdInfo &info = it->second;
    auto now = std::chrono::steady_clock::now();
    auto cutoff = now - info.timeWindow;

    // 计算时间窗口内的异常次数
    size_t count = 0;
    for (const auto &occurrence: info.occurrences) {
        if (occurrence >= cutoff) {
            count++;
        }
    }

    return count >= info.threshold;
}

bool ExceptionHandler::isSystemHealthy() const {
    return healthScore_.load() > 0.5 && !shuttingDown_.load();
}

double ExceptionHandler::getSystemHealthScore() const {
    return healthScore_.load();
}

void ExceptionHandler::resetHealthMetrics() {
    std::lock_guard<std::mutex> lock(mutex_);

    healthScore_.store(1.0);
    totalExceptions_.store(0);
    exceptionCounts_.clear();
    recentExceptions_.clear();
    thresholds_.clear();

    LOGI("健康指标已重置");
}

bool ExceptionHandler::validateMemoryAccess(void *ptr, size_t size) {
    if (!ptr) {
        LOGE("空指针访问");
        return false;
    }

    if (size == 0) {
        LOGW("零大小内存访问");
        return true; // 技术上有效，但可能是逻辑错误
    }

    // 基本的指针有效性检查
    // 注意：这里的检查比较基础，实际应用中可能需要更复杂的验证
    try {
        volatile char test = *static_cast<char *>(ptr);
        (void) test; // 避免未使用变量警告
        return true;
    } catch (...) {
        LOGE("内存访问异常: ptr=%p, size=%zu", ptr, size);
        return false;
    }
}

bool ExceptionHandler::validateBufferBounds(void *buffer, size_t bufferSize, size_t offset,
                                            size_t accessSize) {
    if (!buffer) {
        LOGE("缓冲区指针为空");
        return false;
    }

    if (offset >= bufferSize) {
        LOGE("偏移量超出缓冲区范围: offset=%zu, bufferSize=%zu", offset, bufferSize);
        return false;
    }

    if (offset + accessSize > bufferSize) {
        LOGE("访问大小超出缓冲区范围: offset=%zu, accessSize=%zu, bufferSize=%zu",
             offset, accessSize, bufferSize);
        return false;
    }

    return true;
}

void ExceptionHandler::initiateGracefulShutdown(const std::string &reason) {
    if (shuttingDown_.exchange(true)) {
        return; // 已经在关闭中
    }

    LOGE("启动优雅关闭: %s", reason.c_str());

    // 这里可以添加清理逻辑
    // 例如：保存状态、释放资源、通知其他组件等
}

bool ExceptionHandler::isShuttingDown() const {
    return shuttingDown_.load();
}

void ExceptionHandler::logException(const ExceptionInfo &info) {
    std::string severityStr;
    switch (info.severity) {
        case ExceptionSeverity::LOW:
            severityStr = "LOW";
            break;
        case ExceptionSeverity::MEDIUM:
            severityStr = "MEDIUM";
            break;
        case ExceptionSeverity::HIGH:
            severityStr = "HIGH";
            break;
        case ExceptionSeverity::CRITICAL:
            severityStr = "CRITICAL";
            break;
    }

    std::string typeStr;
    switch (info.type) {
        case ExceptionType::MEMORY_ALLOCATION_FAILED:
            typeStr = "MEMORY_ALLOCATION_FAILED";
            break;
        case ExceptionType::MEMORY_LIMIT_EXCEEDED:
            typeStr = "MEMORY_LIMIT_EXCEEDED";
            break;
        case ExceptionType::MEMORY_CORRUPTION:
            typeStr = "MEMORY_CORRUPTION";
            break;
        case ExceptionType::OUT_OF_MEMORY:
            typeStr = "OUT_OF_MEMORY";
            break;
        case ExceptionType::BUFFER_OVERFLOW:
            typeStr = "BUFFER_OVERFLOW";
            break;
        case ExceptionType::INVALID_PARAMETER:
            typeStr = "INVALID_PARAMETER";
            break;
        case ExceptionType::PROCESSING_ERROR:
            typeStr = "PROCESSING_ERROR";
            break;
        case ExceptionType::SYSTEM_ERROR:
            typeStr = "SYSTEM_ERROR";
            break;
        default:
            typeStr = "UNKNOWN_ERROR";
            break;
    }

    if (info.severity >= ExceptionSeverity::HIGH) {
        LOGE("[%s] %s: %s %s", severityStr.c_str(), typeStr.c_str(),
             info.message.c_str(), info.location.c_str());
    } else if (info.severity == ExceptionSeverity::MEDIUM) {
        LOGW("[%s] %s: %s %s", severityStr.c_str(), typeStr.c_str(),
             info.message.c_str(), info.location.c_str());
    } else {
        LOGI("[%s] %s: %s %s", severityStr.c_str(), typeStr.c_str(),
             info.message.c_str(), info.location.c_str());
    }
}

void ExceptionHandler::updateHealthMetrics(const ExceptionInfo &info) {
    // 根据异常严重程度调整健康分数
    double impact = 0.0;
    switch (info.severity) {
        case ExceptionSeverity::LOW:
            impact = 0.01;
            break;
        case ExceptionSeverity::MEDIUM:
            impact = 0.05;
            break;
        case ExceptionSeverity::HIGH:
            impact = 0.15;
            break;
        case ExceptionSeverity::CRITICAL:
            impact = 0.5;
            break;
    }

    double currentScore = healthScore_.load();
    double newScore = std::max(0.0, currentScore - impact);
    healthScore_.store(newScore);

    // 更新阈值统计
    auto it = thresholds_.find(info.type);
    if (it != thresholds_.end()) {
        it->second.occurrences.push_back(info.timestamp);

        // 清理过期的记录
        auto cutoff = info.timestamp - it->second.timeWindow;
        auto &occurrences = it->second.occurrences;
        occurrences.erase(
                std::remove_if(occurrences.begin(), occurrences.end(),
                               [cutoff](const auto &time) { return time < cutoff; }),
                occurrences.end());
    }

    lastHealthUpdate_ = info.timestamp;
}

void ExceptionHandler::cleanupOldExceptions() {
    auto now = std::chrono::steady_clock::now();
    auto cutoff = now - EXCEPTION_CLEANUP_INTERVAL;

    recentExceptions_.erase(
            std::remove_if(recentExceptions_.begin(), recentExceptions_.end(),
                           [cutoff](const ExceptionInfo &info) {
                               return info.timestamp < cutoff;
                           }),
            recentExceptions_.end());
}

ExceptionSeverity
ExceptionHandler::calculateSeverity(ExceptionType type, const std::string & /* message */) {
    switch (type) {
        case ExceptionType::MEMORY_CORRUPTION:
        case ExceptionType::BUFFER_OVERFLOW:
            return ExceptionSeverity::CRITICAL;

        case ExceptionType::OUT_OF_MEMORY:
        case ExceptionType::MEMORY_LIMIT_EXCEEDED:
            return ExceptionSeverity::HIGH;

        case ExceptionType::MEMORY_ALLOCATION_FAILED:
        case ExceptionType::SYSTEM_ERROR:
            return ExceptionSeverity::MEDIUM;

        case ExceptionType::PROCESSING_ERROR:
        case ExceptionType::INVALID_PARAMETER:
            return ExceptionSeverity::LOW;

        default:
            return ExceptionSeverity::MEDIUM;
    }
}

ExceptionHandler::~ExceptionHandler() {
    if (shuttingDown_.load()) {
        LOGI("ExceptionHandler 析构完成");
    }
}