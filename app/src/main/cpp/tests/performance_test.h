#ifndef PERFORMANCE_TEST_H
#define PERFORMANCE_TEST_H

#include <chrono>
#include <vector>
#include <string>
#include <memory>
#include <functional>
#include <map>

// 前向声明
class MemoryManager;

class LutImageProcessor;

struct MediaFrame;
struct ProcessingConfig;

// 性能测试结果结构
struct PerformanceResult {
    std::string testName;
    double averageTimeMs = 0.0;
    double minTimeMs = 0.0;
    double maxTimeMs = 0.0;
    double standardDeviation = 0.0;
    size_t iterations = 0;

    // 内存相关指标
    size_t peakMemoryUsage = 0;
    size_t averageMemoryUsage = 0;
    size_t memoryLeaks = 0;

    // 处理相关指标
    size_t successfulProcessing = 0;
    size_t failedProcessing = 0;
    double successRate = 0.0;

    // 额外指标
    std::map<std::string, double> customMetrics;

    bool isValid() const {
        return iterations > 0 && !testName.empty();
    }

    void calculateDerivedMetrics() {
        if (iterations > 0) {
            successRate = static_cast<double>(successfulProcessing) / iterations * 100.0;
        }
    }
};

// 测试配置结构
struct TestConfig {
    size_t iterations = 100;
    size_t warmupIterations = 10;
    bool enableMemoryTracking = true;
    bool enableDetailedLogging = false;
    std::chrono::milliseconds timeout{30000};

    // 测试图像配置
    std::vector<std::pair<int, int>> imageSizes = {
            {1920, 1080},   // Full HD
            {2560, 1440},   // 2K
            {3840, 2160},   // 4K
            {7680, 4320}    // 8K
    };

    // 内存限制测试
    std::vector<size_t> memoryLimits = {
            64 * 1024 * 1024,   // 64MB
            128 * 1024 * 1024,  // 128MB
            256 * 1024 * 1024,  // 256MB
            512 * 1024 * 1024   // 512MB
    };
};

// 性能测试套件
class PerformanceTestSuite {
public:
    PerformanceTestSuite();

    ~PerformanceTestSuite();

    // 测试配置
    void setTestConfig(const TestConfig &config);

    TestConfig getTestConfig() const;

    // 基础性能测试
    PerformanceResult testMemoryAllocation();

    PerformanceResult testMemoryDeallocation();

    PerformanceResult testMemoryPoolPerformance();

    PerformanceResult testStreamingProcessorPerformance();

    // LUT处理性能测试
    PerformanceResult testLutProcessingPerformance();

    PerformanceResult testBatchProcessingPerformance();

    PerformanceResult testAsyncProcessingPerformance();

    // 内存压力测试
    PerformanceResult testMemoryPressureHandling();

    PerformanceResult testLargeImageProcessing();

    PerformanceResult testMemoryLeakDetection();

    // 并发性能测试
    PerformanceResult testMultiThreadedProcessing();

    PerformanceResult testConcurrentMemoryAccess();

    // 异常处理性能测试
    PerformanceResult testExceptionHandlingOverhead();

    PerformanceResult testErrorRecoveryPerformance();

    // 综合测试
    std::vector<PerformanceResult> runAllTests();

    std::vector<PerformanceResult> runMemoryTests();

    std::vector<PerformanceResult> runProcessingTests();

    // 结果分析
    void
    generateReport(const std::vector<PerformanceResult> &results, const std::string &outputPath);

    void printSummary(const std::vector<PerformanceResult> &results);

    // 比较测试
    PerformanceResult compareWithBaseline(const std::string &testName,
                                          std::function<void()> testFunction,
                                          std::function<void()> baselineFunction);

    // 回归测试
    bool validatePerformanceRegression(const std::vector<PerformanceResult> &currentResults,
                                       const std::vector<PerformanceResult> &baselineResults,
                                       double tolerancePercent = 10.0);

private:
    TestConfig config_;
    std::unique_ptr<MemoryManager> memoryManager_;
    std::unique_ptr<LutImageProcessor> processor_;

    // 内部测试辅助方法
    PerformanceResult runTimedTest(const std::string &testName,
                                   std::function<bool()> testFunction,
                                   size_t iterations = 0);

    PerformanceResult runMemoryTest(const std::string &testName,
                                    std::function<bool()> testFunction,
                                    size_t iterations = 0);

    std::unique_ptr<MediaFrame> createTestImage(int width, int height);

    std::vector<std::unique_ptr<MediaFrame>>
    createTestImageBatch(size_t count, int width, int height);

    void setupTestEnvironment();

    void cleanupTestEnvironment();

    // 内存监控
    size_t getCurrentMemoryUsage();

    void startMemoryMonitoring();

    void stopMemoryMonitoring();

    // 统计计算
    double calculateStandardDeviation(const std::vector<double> &values, double mean);

    void calculateStatistics(PerformanceResult &result, const std::vector<double> &timings);

    // 报告生成
    void generateHTMLReport(const std::vector<PerformanceResult> &results,
                            const std::string &outputPath);

    void
    generateCSVReport(const std::vector<PerformanceResult> &results, const std::string &outputPath);

    void generateJSONReport(const std::vector<PerformanceResult> &results,
                            const std::string &outputPath);

    // 内存监控数据
    std::vector<size_t> memoryUsageHistory_;
    std::chrono::high_resolution_clock::time_point monitoringStartTime_;
    bool memoryMonitoringActive_ = false;
};

// 基准测试工具
class BenchmarkTool {
public:
    // 简单计时器
    class Timer {
    public:
        Timer() : start_(std::chrono::high_resolution_clock::now()) {}

        double elapsedMs() const {
            auto end = std::chrono::high_resolution_clock::now();
            auto duration = std::chrono::duration_cast<std::chrono::microseconds>(end - start_);
            return duration.count() / 1000.0;
        }

        void reset() {
            start_ = std::chrono::high_resolution_clock::now();
        }

    private:
        std::chrono::high_resolution_clock::time_point start_;
    };

    // 内存使用监控器
    class MemoryMonitor {
    public:
        MemoryMonitor(MemoryManager *manager) : manager_(manager) {
            if (manager_) {
                initialUsage_ = manager_->getTotalAllocatedBytes();
            }
        }

        ~MemoryMonitor() {
            if (manager_) {
                finalUsage_ = manager_->getTotalAllocatedBytes();
            }
        }

        size_t getMemoryDelta() const {
            return finalUsage_ > initialUsage_ ? finalUsage_ - initialUsage_ : 0;
        }

        size_t getPeakUsage() const {
            return manager_ ? manager_->getPeakMemoryUsage() : 0;
        }

    private:
        MemoryManager *manager_;
        size_t initialUsage_ = 0;
        size_t finalUsage_ = 0;
    };

    // 批量测试执行器
    template<typename Func>
    static PerformanceResult
    runBenchmark(const std::string &name, Func &&func, size_t iterations = 100) {
        PerformanceResult result;
        result.testName = name;
        result.iterations = iterations;

        std::vector<double> timings;
        timings.reserve(iterations);

        for (size_t i = 0; i < iterations; ++i) {
            Timer timer;
            bool success = func();
            double elapsed = timer.elapsedMs();

            timings.push_back(elapsed);

            if (success) {
                result.successfulProcessing++;
            } else {
                result.failedProcessing++;
            }
        }

        // 计算统计数据
        if (!timings.empty()) {
            result.averageTimeMs =
                    std::accumulate(timings.begin(), timings.end(), 0.0) / timings.size();
            result.minTimeMs = *std::min_element(timings.begin(), timings.end());
            result.maxTimeMs = *std::max_element(timings.begin(), timings.end());

            // 计算标准差
            double variance = 0.0;
            for (double time: timings) {
                variance += (time - result.averageTimeMs) * (time - result.averageTimeMs);
            }
            result.standardDeviation = std::sqrt(variance / timings.size());
        }

        result.calculateDerivedMetrics();
        return result;
    }
};

// 性能测试宏定义
#define BENCHMARK_TEST(name, iterations, code) \
    BenchmarkTool::runBenchmark(name, [&]() -> bool { \
        try { \
            code; \
            return true; \
        } catch (...) { \
            return false; \
        } \
    }, iterations)

#define MEMORY_BENCHMARK_TEST(name, iterations, manager, code) \
    [&]() -> PerformanceResult { \
        PerformanceResult result; \
        result.testName = name; \
        result.iterations = iterations; \
        std::vector<double> timings; \
        for (size_t i = 0; i < iterations; ++i) { \
            BenchmarkTool::MemoryMonitor monitor(manager); \
            BenchmarkTool::Timer timer; \
            try { \
                code; \
                result.successfulProcessing++; \
            } catch (...) { \
                result.failedProcessing++; \
            } \
            timings.push_back(timer.elapsedMs()); \
            result.peakMemoryUsage = std::max(result.peakMemoryUsage, monitor.getPeakUsage()); \
        } \
        if (!timings.empty()) { \
            result.averageTimeMs = std::accumulate(timings.begin(), timings.end(), 0.0) / timings.size(); \
        } \
        result.calculateDerivedMetrics(); \
        return result; \
    }()

// 便利函数
namespace PerformanceTestUtils {
    // 创建默认测试配置
    TestConfig createDefaultTestConfig();

    TestConfig createQuickTestConfig();

    TestConfig createStressTestConfig();

    // 测试数据生成
    std::vector<uint8_t> generateTestImageData(int width, int height, int channels);

    std::unique_ptr<MediaFrame> createRandomTestImage(int width, int height);

    // 结果比较
    bool
    compareResults(const PerformanceResult &a, const PerformanceResult &b, double tolerance = 5.0);

    double calculatePerformanceImprovement(const PerformanceResult &baseline,
                                           const PerformanceResult &optimized);

    // 报告工具
    void printResultTable(const std::vector<PerformanceResult> &results);

    void
    saveResultsToFile(const std::vector<PerformanceResult> &results, const std::string &filename);

    std::vector<PerformanceResult> loadResultsFromFile(const std::string &filename);
}

#endif // PERFORMANCE_TEST_H