#ifndef EXCEPTION_HANDLER_H
#define EXCEPTION_HANDLER_H

#include <exception>
#include <string>
#include <functional>
#include <memory>
#include <vector>
#include <mutex>
#include <atomic>
#include <chrono>

// 异常类型枚举
enum class ExceptionType {
    MEMORY_ALLOCATION_FAILED,
    MEMORY_LIMIT_EXCEEDED,
    MEMORY_CORRUPTION,
    OUT_OF_MEMORY,
    BUFFER_OVERFLOW,
    INVALID_PARAMETER,
    PROCESSING_ERROR,
    SYSTEM_ERROR,
    UNKNOWN_ERROR
};

// 异常严重级别
enum class ExceptionSeverity {
    LOW,        // 可恢复的轻微错误
    MEDIUM,     // 需要降级处理的错误
    HIGH,       // 严重错误，需要立即处理
    CRITICAL    // 致命错误，可能导致崩溃
};

// 降级策略
enum class FallbackStrategy {
    RETRY,              // 重试操作
    REDUCE_QUALITY,     // 降低质量
    USE_CPU_FALLBACK,   // 使用CPU回退
    SPLIT_PROCESSING,   // 分块处理
    SKIP_OPERATION,     // 跳过操作
    TERMINATE_GRACEFULLY // 优雅终止
};

// 异常信息结构
struct ExceptionInfo {
    ExceptionType type;
    ExceptionSeverity severity;
    std::string message;
    std::string location; // 文件:行号
    size_t memoryRequested = 0;
    size_t memoryAvailable = 0;
    std::chrono::steady_clock::time_point timestamp;

    ExceptionInfo(ExceptionType t, ExceptionSeverity s, const std::string &msg,
                  const std::string &loc = "")
            : type(t), severity(s), message(msg), location(loc),
              timestamp(std::chrono::steady_clock::now()) {}
};

// 异常处理回调
typedef std::function<bool(const ExceptionInfo &)> ExceptionCallback;

// 降级处理回调
typedef std::function<bool(FallbackStrategy, const ExceptionInfo &)> FallbackCallback;

/**
 * 自定义异常类
 */
class MemoryException : public std::exception {
public:
    MemoryException(ExceptionType type, const std::string &message,
                    const std::string &location = "")
            : type_(type), message_(message), location_(location) {
        fullMessage_ = "[" + getTypeString() + "] " + message_;
        if (!location_.empty()) {
            fullMessage_ += " at " + location_;
        }
    }

    const char *what() const noexcept override {
        return fullMessage_.c_str();
    }

    ExceptionType getType() const { return type_; }

    const std::string &getMessage() const { return message_; }

    const std::string &getLocation() const { return location_; }

private:
    ExceptionType type_;
    std::string message_;
    std::string location_;
    std::string fullMessage_;

    std::string getTypeString() const {
        switch (type_) {
            case ExceptionType::MEMORY_ALLOCATION_FAILED:
                return "MEMORY_ALLOCATION_FAILED";
            case ExceptionType::MEMORY_LIMIT_EXCEEDED:
                return "MEMORY_LIMIT_EXCEEDED";
            case ExceptionType::MEMORY_CORRUPTION:
                return "MEMORY_CORRUPTION";
            case ExceptionType::OUT_OF_MEMORY:
                return "OUT_OF_MEMORY";
            case ExceptionType::BUFFER_OVERFLOW:
                return "BUFFER_OVERFLOW";
            case ExceptionType::INVALID_PARAMETER:
                return "INVALID_PARAMETER";
            case ExceptionType::PROCESSING_ERROR:
                return "PROCESSING_ERROR";
            case ExceptionType::SYSTEM_ERROR:
                return "SYSTEM_ERROR";
            default:
                return "UNKNOWN_ERROR";
        }
    }
};

/**
 * 异常处理器 - 单例模式
 * 提供统一的异常处理、降级策略和恢复机制
 */
class ExceptionHandler {
public:
    static ExceptionHandler &getInstance();

    // 异常处理
    void handleException(const ExceptionInfo &info);

    void handleException(ExceptionType type, ExceptionSeverity severity,
                         const std::string &message, const std::string &location = "");

    // 回调管理
    void setExceptionCallback(ExceptionCallback callback);

    void setFallbackCallback(FallbackCallback callback);

    void removeCallbacks();

    // 降级策略
    bool executeFallback(FallbackStrategy strategy, const ExceptionInfo &info);

    FallbackStrategy selectFallbackStrategy(const ExceptionInfo &info);

    // 异常统计
    size_t getExceptionCount(ExceptionType type = ExceptionType::UNKNOWN_ERROR) const;

    size_t getTotalExceptions() const;

    std::vector<ExceptionInfo> getRecentExceptions(size_t count = 10) const;

    // 异常阈值管理
    void
    setExceptionThreshold(ExceptionType type, size_t threshold, std::chrono::seconds timeWindow);

    bool isExceptionThresholdExceeded(ExceptionType type) const;

    // 系统健康检查
    bool isSystemHealthy() const;

    double getSystemHealthScore() const;

    void resetHealthMetrics();

    // 内存安全检查
    bool validateMemoryAccess(void *ptr, size_t size);

    bool validateBufferBounds(void *buffer, size_t bufferSize, size_t offset, size_t accessSize);

    // 优雅关闭
    void initiateGracefulShutdown(const std::string &reason);

    bool isShuttingDown() const;

    ~ExceptionHandler();

private:
    ExceptionHandler() = default;

    ExceptionHandler(const ExceptionHandler &) = delete;

    ExceptionHandler &operator=(const ExceptionHandler &) = delete;

    // 内部方法
    void logException(const ExceptionInfo &info);

    void updateHealthMetrics(const ExceptionInfo &info);

    void cleanupOldExceptions();

    ExceptionSeverity calculateSeverity(ExceptionType type, const std::string &message);

    // 成员变量
    mutable std::mutex mutex_;
    ExceptionCallback exceptionCallback_;
    FallbackCallback fallbackCallback_;

    // 异常统计
    std::vector<ExceptionInfo> recentExceptions_;
    std::atomic<size_t> totalExceptions_{0};
    std::unordered_map<ExceptionType, std::atomic<size_t>> exceptionCounts_;

    // 阈值管理
    struct ThresholdInfo {
        size_t threshold;
        std::chrono::seconds timeWindow;
        std::vector<std::chrono::steady_clock::time_point> occurrences;
    };
    std::unordered_map<ExceptionType, ThresholdInfo> thresholds_;

    // 系统健康
    std::atomic<double> healthScore_{1.0};
    std::atomic<bool> shuttingDown_{false};
    std::chrono::steady_clock::time_point lastHealthUpdate_;

    // 配置参数
    static constexpr size_t MAX_RECENT_EXCEPTIONS = 100;
    static constexpr std::chrono::minutes EXCEPTION_CLEANUP_INTERVAL{5};
    static constexpr double HEALTH_DECAY_RATE = 0.95;
};

// 便利宏定义
#define THROW_MEMORY_EXCEPTION(type, message) \
    throw MemoryException(type, message, __FILE__ ":" + std::to_string(__LINE__))

#define HANDLE_EXCEPTION(type, severity, message) \
    ExceptionHandler::getInstance().handleException(type, severity, message, __FILE__ ":" + std::to_string(__LINE__))

#define VALIDATE_POINTER(ptr, size) \
    if (!ExceptionHandler::getInstance().validateMemoryAccess(ptr, size)) { \
        THROW_MEMORY_EXCEPTION(ExceptionType::MEMORY_CORRUPTION, "Invalid memory access"); \
    }

#define VALIDATE_BUFFER_BOUNDS(buffer, bufferSize, offset, accessSize) \
    if (!ExceptionHandler::getInstance().validateBufferBounds(buffer, bufferSize, offset, accessSize)) { \
        THROW_MEMORY_EXCEPTION(ExceptionType::BUFFER_OVERFLOW, "Buffer bounds violation"); \
    }

// RAII异常安全包装器
template<typename T>
class SafeWrapper {
public:
    template<typename... Args>
    SafeWrapper(Args &&... args) {
        try {
            resource_ = std::make_unique<T>(std::forward<Args>(args)...);
        } catch (const std::exception &e) {
            HANDLE_EXCEPTION(ExceptionType::PROCESSING_ERROR, ExceptionSeverity::HIGH,
                             std::string("Resource creation failed: ") + e.what());
            throw;
        }
    }

    T *get() const { return resource_.get(); }

    T *operator->() const { return resource_.get(); }

    T &operator*() const { return *resource_; }

    bool isValid() const { return resource_ != nullptr; }

private:
    std::unique_ptr<T> resource_;
};

#endif // EXCEPTION_HANDLER_H