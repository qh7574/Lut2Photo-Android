#include "performance_test.h"
#include "../memory_manager.h"
#include "../lut_image_processor.h"
#include "../media_processor_interface.h"
#include "../exception_handler.h"

#include <algorithm>
#include <numeric>
#include <fstream>
#include <sstream>
#include <iomanip>
#include <thread>
#include <future>
#include <random>
#include <cmath>

#ifdef __ANDROID__

#include <android/log.h>

#define LOG_TAG "PerformanceTest"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#include <iostream>
#define LOGI(...) printf(__VA_ARGS__); printf("\n")
#define LOGE(...) fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n")
#endif

// PerformanceTestSuite 实现
PerformanceTestSuite::PerformanceTestSuite() {
    setupTestEnvironment();
}

PerformanceTestSuite::~PerformanceTestSuite() {
    cleanupTestEnvironment();
}

void PerformanceTestSuite::setTestConfig(const TestConfig &config) {
    config_ = config;
}

TestConfig PerformanceTestSuite::getTestConfig() const {
    return config_;
}

PerformanceResult PerformanceTestSuite::testMemoryAllocation() {
    return runMemoryTest("Memory Allocation", [this]() -> bool {
        try {
            size_t size = 1024 * 1024; // 1MB
            void *ptr = memoryManager_->allocate(size);
            if (!ptr) return false;

            // 写入数据验证分配成功
            memset(ptr, 0xAA, size);

            memoryManager_->deallocate(ptr, size);
            return true;
        } catch (...) {
            return false;
        }
    });
}

PerformanceResult PerformanceTestSuite::testMemoryDeallocation() {
    return runMemoryTest("Memory Deallocation", [this]() -> bool {
        try {
            size_t size = 1024 * 1024; // 1MB
            void *ptr = memoryManager_->allocate(size);
            if (!ptr) return false;

            BenchmarkTool::Timer timer;
            memoryManager_->deallocate(ptr, size);

            return timer.elapsedMs() < 10.0; // 期望释放时间小于10ms
        } catch (...) {
            return false;
        }
    });
}

PerformanceResult PerformanceTestSuite::testMemoryPoolPerformance() {
    return runMemoryTest("Memory Pool Performance", [this]() -> bool {
        try {
            size_t size = 64 * 1024; // 64KB - 适合内存池
            void *ptr = memoryManager_->smartAllocate(size);
            if (!ptr) return false;

            // 验证是否从内存池分配
            size_t poolAllocated = memoryManager_->getPoolAllocatedBytes();

            memoryManager_->deallocate(ptr, size);
            return poolAllocated > 0;
        } catch (...) {
            return false;
        }
    });
}

PerformanceResult PerformanceTestSuite::testStreamingProcessorPerformance() {
    return runTimedTest("Streaming Processor Performance", [this]() -> bool {
        try {
            auto testImage = createTestImage(1920, 1080);
            if (!testImage) return false;

            ProcessingConfig config;
            config.enableStreaming = true;
            config.memoryLimit = 128 * 1024 * 1024; // 128MB

            processor_->updateConfig(config);

            auto result = processor_->processFrame(*testImage);
            return result != nullptr;
        } catch (...) {
            return false;
        }
    });
}

PerformanceResult PerformanceTestSuite::testLutProcessingPerformance() {
    return runTimedTest("LUT Processing Performance", [this]() -> bool {
        try {
            auto testImage = createTestImage(1920, 1080);
            if (!testImage) return false;

            // 创建测试LUT
            std::vector<float> lutData(256 * 256 * 256 * 3);
            std::iota(lutData.begin(), lutData.end(), 0.0f);

            processor_->loadLut(lutData.data(), 256);

            auto result = processor_->processFrame(*testImage);
            return result != nullptr;
        } catch (...) {
            return false;
        }
    });
}

PerformanceResult PerformanceTestSuite::testBatchProcessingPerformance() {
    return runTimedTest("Batch Processing Performance", [this]() -> bool {
        try {
            auto testBatch = createTestImageBatch(10, 1920, 1080);
            if (testBatch.empty()) return false;

            std::vector<MediaFrame *> frames;
            for (auto &frame: testBatch) {
                frames.push_back(frame.get());
            }

            auto results = processor_->processBatch(frames);
            return results.size() == frames.size();
        } catch (...) {
            return false;
        }
    });
}

PerformanceResult PerformanceTestSuite::testAsyncProcessingPerformance() {
    return runTimedTest("Async Processing Performance", [this]() -> bool {
        try {
            auto testImage = createTestImage(1920, 1080);
            if (!testImage) return false;

            auto future = processor_->processFrameAsync(*testImage);
            auto result = future.get();

            return result != nullptr;
        } catch (...) {
            return false;
        }
    });
}

PerformanceResult PerformanceTestSuite::testMemoryPressureHandling() {
    return runMemoryTest("Memory Pressure Handling", [this]() -> bool {
        try {
            // 设置较低的内存限制
            memoryManager_->setMemoryLimit(64 * 1024 * 1024); // 64MB

            // 尝试分配大量内存触发压力处理
            std::vector<void *> allocations;
            size_t allocationSize = 8 * 1024 * 1024; // 8MB

            for (int i = 0; i < 10; ++i) {
                void *ptr = memoryManager_->allocate(allocationSize);
                if (ptr) {
                    allocations.push_back(ptr);
                }
            }

            // 检查是否触发了内存压力处理
            bool pressureHandled = memoryManager_->isMemoryPressureHigh();

            // 清理分配的内存
            for (void *ptr: allocations) {
                memoryManager_->deallocate(ptr, allocationSize);
            }

            return pressureHandled;
        } catch (...) {
            return false;
        }
    });
}

PerformanceResult PerformanceTestSuite::testLargeImageProcessing() {
    return runTimedTest("Large Image Processing", [this]() -> bool {
        try {
            auto testImage = createTestImage(7680, 4320); // 8K image
            if (!testImage) return false;

            ProcessingConfig config;
            config.enableStreaming = true;
            config.memoryLimit = 512 * 1024 * 1024; // 512MB

            processor_->updateConfig(config);

            auto result = processor_->processFrame(*testImage);
            return result != nullptr;
        } catch (...) {
            return false;
        }
    });
}

PerformanceResult PerformanceTestSuite::testMemoryLeakDetection() {
    return runMemoryTest("Memory Leak Detection", [this]() -> bool {
        try {
            size_t initialMemory = memoryManager_->getTotalAllocatedBytes();

            // 执行一系列分配和释放操作
            for (int i = 0; i < 100; ++i) {
                size_t size = (i + 1) * 1024; // 递增大小
                void *ptr = memoryManager_->allocate(size);
                if (ptr) {
                    memoryManager_->deallocate(ptr, size);
                }
            }

            size_t finalMemory = memoryManager_->getTotalAllocatedBytes();

            // 检查是否有内存泄漏
            return finalMemory <= initialMemory + 1024; // 允许少量误差
        } catch (...) {
            return false;
        }
    });
}

PerformanceResult PerformanceTestSuite::testMultiThreadedProcessing() {
    return runTimedTest("Multi-threaded Processing", [this]() -> bool {
        try {
            const int numThreads = std::thread::hardware_concurrency();
            std::vector<std::future<bool>> futures;

            for (int i = 0; i < numThreads; ++i) {
                futures.push_back(std::async(std::launch::async, [this]() -> bool {
                    auto testImage = createTestImage(1920, 1080);
                    if (!testImage) return false;

                    auto result = processor_->processFrame(*testImage);
                    return result != nullptr;
                }));
            }

            bool allSucceeded = true;
            for (auto &future: futures) {
                if (!future.get()) {
                    allSucceeded = false;
                }
            }

            return allSucceeded;
        } catch (...) {
            return false;
        }
    });
}

PerformanceResult PerformanceTestSuite::testConcurrentMemoryAccess() {
    return runMemoryTest("Concurrent Memory Access", [this]() -> bool {
        try {
            const int numThreads = std::thread::hardware_concurrency();
            std::vector<std::future<bool>> futures;

            for (int i = 0; i < numThreads; ++i) {
                futures.push_back(std::async(std::launch::async, [this]() -> bool {
                    for (int j = 0; j < 10; ++j) {
                        size_t size = 1024 * (j + 1);
                        void *ptr = memoryManager_->allocate(size);
                        if (!ptr) return false;

                        // 模拟一些工作
                        std::this_thread::sleep_for(std::chrono::microseconds(100));

                        memoryManager_->deallocate(ptr, size);
                    }
                    return true;
                }));
            }

            bool allSucceeded = true;
            for (auto &future: futures) {
                if (!future.get()) {
                    allSucceeded = false;
                }
            }

            return allSucceeded;
        } catch (...) {
            return false;
        }
    });
}

PerformanceResult PerformanceTestSuite::testExceptionHandlingOverhead() {
    return runTimedTest("Exception Handling Overhead", [this]() -> bool {
        try {
            // 测试正常路径的性能
            auto testImage = createTestImage(1920, 1080);
            if (!testImage) return false;

            // 启用异常处理
            auto &exceptionHandler = ExceptionHandler::getInstance();
            exceptionHandler.setExceptionThreshold(ExceptionType::MEMORY_ALLOCATION_FAILED, 5);

            auto result = processor_->processFrame(*testImage);
            return result != nullptr;
        } catch (...) {
            return false;
        }
    });
}

PerformanceResult PerformanceTestSuite::testErrorRecoveryPerformance() {
    return runTimedTest("Error Recovery Performance", [this]() -> bool {
        try {
            // 模拟错误条件
            memoryManager_->setMemoryLimit(1024); // 极低内存限制

            auto testImage = createTestImage(1920, 1080);
            if (!testImage) return false;

            // 尝试处理，应该触发错误恢复
            try {
                auto result = processor_->processFrame(*testImage);
                // 如果成功，说明错误恢复工作正常
                return result != nullptr;
            } catch (...) {
                // 异常被正确处理
                return true;
            }
        } catch (...) {
            return false;
        }
    });
}

std::vector<PerformanceResult> PerformanceTestSuite::runAllTests() {
    std::vector<PerformanceResult> results;

    LOGI("开始运行所有性能测试...");

    // 内存测试
    results.push_back(testMemoryAllocation());
    results.push_back(testMemoryDeallocation());
    results.push_back(testMemoryPoolPerformance());
    results.push_back(testMemoryPressureHandling());
    results.push_back(testMemoryLeakDetection());
    results.push_back(testConcurrentMemoryAccess());

    // 处理测试
    results.push_back(testLutProcessingPerformance());
    results.push_back(testStreamingProcessorPerformance());
    results.push_back(testBatchProcessingPerformance());
    results.push_back(testAsyncProcessingPerformance());
    results.push_back(testLargeImageProcessing());
    results.push_back(testMultiThreadedProcessing());

    // 异常处理测试
    results.push_back(testExceptionHandlingOverhead());
    results.push_back(testErrorRecoveryPerformance());

    LOGI("所有性能测试完成，共 %zu 个测试", results.size());

    return results;
}

std::vector<PerformanceResult> PerformanceTestSuite::runMemoryTests() {
    std::vector<PerformanceResult> results;

    results.push_back(testMemoryAllocation());
    results.push_back(testMemoryDeallocation());
    results.push_back(testMemoryPoolPerformance());
    results.push_back(testMemoryPressureHandling());
    results.push_back(testMemoryLeakDetection());
    results.push_back(testConcurrentMemoryAccess());

    return results;
}

std::vector<PerformanceResult> PerformanceTestSuite::runProcessingTests() {
    std::vector<PerformanceResult> results;

    results.push_back(testLutProcessingPerformance());
    results.push_back(testStreamingProcessorPerformance());
    results.push_back(testBatchProcessingPerformance());
    results.push_back(testAsyncProcessingPerformance());
    results.push_back(testLargeImageProcessing());
    results.push_back(testMultiThreadedProcessing());

    return results;
}

void PerformanceTestSuite::generateReport(const std::vector<PerformanceResult> &results,
                                          const std::string &outputPath) {
    generateHTMLReport(results, outputPath + ".html");
    generateCSVReport(results, outputPath + ".csv");
    generateJSONReport(results, outputPath + ".json");
}

void PerformanceTestSuite::printSummary(const std::vector<PerformanceResult> &results) {
    LOGI("\n=== 性能测试总结 ===");
    LOGI("测试数量: %zu", results.size());

    double totalTime = 0.0;
    size_t totalIterations = 0;
    size_t successfulTests = 0;

    for (const auto &result: results) {
        if (result.isValid()) {
            totalTime += result.averageTimeMs * result.iterations;
            totalIterations += result.iterations;
            if (result.successRate > 90.0) {
                successfulTests++;
            }

            LOGI("%s: 平均 %.2fms, 成功率 %.1f%%",
                 result.testName.c_str(), result.averageTimeMs, result.successRate);
        }
    }

    LOGI("\n总体统计:");
    LOGI("总执行时间: %.2fms", totalTime);
    LOGI("总迭代次数: %zu", totalIterations);
    LOGI("成功测试数: %zu/%zu (%.1f%%)",
         successfulTests, results.size(),
         static_cast<double>(successfulTests) / results.size() * 100.0);
}

// 私有方法实现
PerformanceResult PerformanceTestSuite::runTimedTest(const std::string &testName,
                                                     std::function<bool()> testFunction,
                                                     size_t iterations) {
    if (iterations == 0) {
        iterations = config_.iterations;
    }

    LOGI("运行测试: %s (%zu 次迭代)", testName.c_str(), iterations);

    PerformanceResult result;
    result.testName = testName;
    result.iterations = iterations;

    std::vector<double> timings;
    timings.reserve(iterations);

    // 预热
    for (size_t i = 0; i < config_.warmupIterations; ++i) {
        testFunction();
    }

    // 实际测试
    for (size_t i = 0; i < iterations; ++i) {
        BenchmarkTool::Timer timer;
        bool success = testFunction();
        double elapsed = timer.elapsedMs();

        timings.push_back(elapsed);

        if (success) {
            result.successfulProcessing++;
        } else {
            result.failedProcessing++;
        }
    }

    calculateStatistics(result, timings);
    result.calculateDerivedMetrics();

    return result;
}

PerformanceResult PerformanceTestSuite::runMemoryTest(const std::string &testName,
                                                      std::function<bool()> testFunction,
                                                      size_t iterations) {
    if (iterations == 0) {
        iterations = config_.iterations;
    }

    LOGI("运行内存测试: %s (%zu 次迭代)", testName.c_str(), iterations);

    PerformanceResult result;
    result.testName = testName;
    result.iterations = iterations;

    std::vector<double> timings;
    std::vector<size_t> memoryUsages;
    timings.reserve(iterations);
    memoryUsages.reserve(iterations);

    size_t initialMemory = memoryManager_->getTotalAllocatedBytes();

    // 预热
    for (size_t i = 0; i < config_.warmupIterations; ++i) {
        testFunction();
    }

    // 实际测试
    for (size_t i = 0; i < iterations; ++i) {
        size_t memoryBefore = memoryManager_->getTotalAllocatedBytes();

        BenchmarkTool::Timer timer;
        bool success = testFunction();
        double elapsed = timer.elapsedMs();

        size_t memoryAfter = memoryManager_->getTotalAllocatedBytes();
        size_t memoryDelta = memoryAfter > memoryBefore ? memoryAfter - memoryBefore : 0;

        timings.push_back(elapsed);
        memoryUsages.push_back(memoryDelta);

        if (success) {
            result.successfulProcessing++;
        } else {
            result.failedProcessing++;
        }
    }

    calculateStatistics(result, timings);

    // 计算内存统计
    if (!memoryUsages.empty()) {
        result.averageMemoryUsage =
                std::accumulate(memoryUsages.begin(), memoryUsages.end(), 0ULL) /
                memoryUsages.size();
        result.peakMemoryUsage = *std::max_element(memoryUsages.begin(), memoryUsages.end());
    }

    size_t finalMemory = memoryManager_->getTotalAllocatedBytes();
    result.memoryLeaks = finalMemory > initialMemory ? finalMemory - initialMemory : 0;

    result.calculateDerivedMetrics();

    return result;
}

std::unique_ptr<MediaFrame> PerformanceTestSuite::createTestImage(int width, int height) {
    auto frame = std::make_unique<MediaFrame>();
    frame->width = width;
    frame->height = height;
    frame->format = PixelFormat::RGBA8888;
    frame->channels = 4;

    size_t dataSize = width * height * 4;
    frame->data.resize(dataSize);

    // 填充测试数据
    std::random_device rd;
    std::mt19937 gen(rd());
    std::uniform_int_distribution<uint8_t> dis(0, 255);

    for (size_t i = 0; i < dataSize; ++i) {
        frame->data[i] = dis(gen);
    }

    return frame;
}

std::vector<std::unique_ptr<MediaFrame>>
PerformanceTestSuite::createTestImageBatch(size_t count, int width, int height) {
    std::vector<std::unique_ptr<MediaFrame>> batch;
    batch.reserve(count);

    for (size_t i = 0; i < count; ++i) {
        batch.push_back(createTestImage(width, height));
    }

    return batch;
}

void PerformanceTestSuite::setupTestEnvironment() {
    // 初始化内存管理器
    memoryManager_ = std::make_unique<MemoryManager>();
    memoryManager_->setMemoryLimit(1024 * 1024 * 1024); // 1GB

    // 初始化处理器
    processor_ = std::make_unique<LutImageProcessor>();
    processor_->initialize();

    // 设置默认配置
    config_ = PerformanceTestUtils::createDefaultTestConfig();

    LOGI("测试环境初始化完成");
}

void PerformanceTestSuite::cleanupTestEnvironment() {
    if (processor_) {
        processor_->cleanup();
        processor_.reset();
    }

    if (memoryManager_) {
        memoryManager_.reset();
    }

    LOGI("测试环境清理完成");
}

void PerformanceTestSuite::calculateStatistics(PerformanceResult &result,
                                               const std::vector<double> &timings) {
    if (timings.empty()) return;

    result.averageTimeMs = std::accumulate(timings.begin(), timings.end(), 0.0) / timings.size();
    result.minTimeMs = *std::min_element(timings.begin(), timings.end());
    result.maxTimeMs = *std::max_element(timings.begin(), timings.end());

    // 计算标准差
    double variance = 0.0;
    for (double time: timings) {
        variance += (time - result.averageTimeMs) * (time - result.averageTimeMs);
    }
    result.standardDeviation = std::sqrt(variance / timings.size());
}

void PerformanceTestSuite::generateHTMLReport(const std::vector<PerformanceResult> &results,
                                              const std::string &outputPath) {
    std::ofstream file(outputPath);
    if (!file.is_open()) {
        LOGE("无法创建HTML报告文件: %s", outputPath.c_str());
        return;
    }

    file << "<!DOCTYPE html>\n<html>\n<head>\n";
    file << "<title>性能测试报告</title>\n";
    file << "<style>\n";
    file << "body { font-family: Arial, sans-serif; margin: 20px; }\n";
    file << "table { border-collapse: collapse; width: 100%; }\n";
    file << "th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }\n";
    file << "th { background-color: #f2f2f2; }\n";
    file << "</style>\n";
    file << "</head>\n<body>\n";
    file << "<h1>性能测试报告</h1>\n";

    file << "<table>\n";
    file << "<tr><th>测试名称</th><th>平均时间(ms)</th><th>最小时间(ms)</th><th>最大时间(ms)</th>";
    file << "<th>标准差</th><th>成功率(%)</th><th>迭代次数</th></tr>\n";

    for (const auto &result: results) {
        if (result.isValid()) {
            file << "<tr>";
            file << "<td>" << result.testName << "</td>";
            file << "<td>" << std::fixed << std::setprecision(2) << result.averageTimeMs << "</td>";
            file << "<td>" << std::fixed << std::setprecision(2) << result.minTimeMs << "</td>";
            file << "<td>" << std::fixed << std::setprecision(2) << result.maxTimeMs << "</td>";
            file << "<td>" << std::fixed << std::setprecision(2) << result.standardDeviation
                 << "</td>";
            file << "<td>" << std::fixed << std::setprecision(1) << result.successRate << "</td>";
            file << "<td>" << result.iterations << "</td>";
            file << "</tr>\n";
        }
    }

    file << "</table>\n";
    file << "</body>\n</html>\n";

    LOGI("HTML报告已生成: %s", outputPath.c_str());
}

void PerformanceTestSuite::generateCSVReport(const std::vector<PerformanceResult> &results,
                                             const std::string &outputPath) {
    std::ofstream file(outputPath);
    if (!file.is_open()) {
        LOGE("无法创建CSV报告文件: %s", outputPath.c_str());
        return;
    }

    // CSV头部
    file
            << "测试名称,平均时间(ms),最小时间(ms),最大时间(ms),标准差,成功率(%),迭代次数,峰值内存(bytes),平均内存(bytes)\n";

    for (const auto &result: results) {
        if (result.isValid()) {
            file << result.testName << ",";
            file << std::fixed << std::setprecision(2) << result.averageTimeMs << ",";
            file << std::fixed << std::setprecision(2) << result.minTimeMs << ",";
            file << std::fixed << std::setprecision(2) << result.maxTimeMs << ",";
            file << std::fixed << std::setprecision(2) << result.standardDeviation << ",";
            file << std::fixed << std::setprecision(1) << result.successRate << ",";
            file << result.iterations << ",";
            file << result.peakMemoryUsage << ",";
            file << result.averageMemoryUsage << "\n";
        }
    }

    LOGI("CSV报告已生成: %s", outputPath.c_str());
}

void PerformanceTestSuite::generateJSONReport(const std::vector<PerformanceResult> &results,
                                              const std::string &outputPath) {
    std::ofstream file(outputPath);
    if (!file.is_open()) {
        LOGE("无法创建JSON报告文件: %s", outputPath.c_str());
        return;
    }

    file << "{\n";
    file << "  \"performance_test_results\": [\n";

    for (size_t i = 0; i < results.size(); ++i) {
        const auto &result = results[i];
        if (result.isValid()) {
            file << "    {\n";
            file << "      \"test_name\": \"" << result.testName << "\",\n";
            file << "      \"average_time_ms\": " << std::fixed << std::setprecision(2)
                 << result.averageTimeMs << ",\n";
            file << "      \"min_time_ms\": " << std::fixed << std::setprecision(2)
                 << result.minTimeMs << ",\n";
            file << "      \"max_time_ms\": " << std::fixed << std::setprecision(2)
                 << result.maxTimeMs << ",\n";
            file << "      \"standard_deviation\": " << std::fixed << std::setprecision(2)
                 << result.standardDeviation << ",\n";
            file << "      \"success_rate\": " << std::fixed << std::setprecision(1)
                 << result.successRate << ",\n";
            file << "      \"iterations\": " << result.iterations << ",\n";
            file << "      \"peak_memory_usage\": " << result.peakMemoryUsage << ",\n";
            file << "      \"average_memory_usage\": " << result.averageMemoryUsage << "\n";
            file << "    }";

            if (i < results.size() - 1) {
                file << ",";
            }
            file << "\n";
        }
    }

    file << "  ]\n";
    file << "}\n";

    LOGI("JSON报告已生成: %s", outputPath.c_str());
}

// PerformanceTestUtils 实现
namespace PerformanceTestUtils {
    TestConfig createDefaultTestConfig() {
        TestConfig config;
        config.iterations = 100;
        config.warmupIterations = 10;
        config.enableMemoryTracking = true;
        config.enableDetailedLogging = false;
        config.timeout = std::chrono::milliseconds(30000);
        return config;
    }

    TestConfig createQuickTestConfig() {
        TestConfig config;
        config.iterations = 10;
        config.warmupIterations = 2;
        config.enableMemoryTracking = true;
        config.enableDetailedLogging = false;
        config.timeout = std::chrono::milliseconds(5000);
        return config;
    }

    TestConfig createStressTestConfig() {
        TestConfig config;
        config.iterations = 1000;
        config.warmupIterations = 50;
        config.enableMemoryTracking = true;
        config.enableDetailedLogging = true;
        config.timeout = std::chrono::milliseconds(300000); // 5分钟
        return config;
    }

    std::vector<uint8_t> generateTestImageData(int width, int height, int channels) {
        size_t dataSize = width * height * channels;
        std::vector<uint8_t> data(dataSize);

        std::random_device rd;
        std::mt19937 gen(rd());
        std::uniform_int_distribution<uint8_t> dis(0, 255);

        for (size_t i = 0; i < dataSize; ++i) {
            data[i] = dis(gen);
        }

        return data;
    }

    std::unique_ptr<MediaFrame> createRandomTestImage(int width, int height) {
        auto frame = std::make_unique<MediaFrame>();
        frame->width = width;
        frame->height = height;
        frame->format = PixelFormat::RGBA8888;
        frame->channels = 4;
        frame->data = generateTestImageData(width, height, 4);
        return frame;
    }

    bool compareResults(const PerformanceResult &a, const PerformanceResult &b, double tolerance) {
        if (a.testName != b.testName) return false;

        double timeDiff = std::abs(a.averageTimeMs - b.averageTimeMs);
        double timeThreshold = std::max(a.averageTimeMs, b.averageTimeMs) * tolerance / 100.0;

        return timeDiff <= timeThreshold;
    }

    double calculatePerformanceImprovement(const PerformanceResult &baseline,
                                           const PerformanceResult &optimized) {
        if (baseline.averageTimeMs == 0.0) return 0.0;

        return ((baseline.averageTimeMs - optimized.averageTimeMs) / baseline.averageTimeMs) *
               100.0;
    }

    void printResultTable(const std::vector<PerformanceResult> &results) {
        LOGI("\n%-30s %-12s %-12s %-12s %-10s %-10s",
             "测试名称", "平均时间(ms)", "最小时间(ms)", "最大时间(ms)", "成功率(%)", "迭代次数");
        LOGI("%-30s %-12s %-12s %-12s %-10s %-10s",
             "------------------------------", "------------", "------------", "------------",
             "----------", "----------");

        for (const auto &result: results) {
            if (result.isValid()) {
                LOGI("%-30s %-12.2f %-12.2f %-12.2f %-10.1f %-10zu",
                     result.testName.c_str(),
                     result.averageTimeMs,
                     result.minTimeMs,
                     result.maxTimeMs,
                     result.successRate,
                     result.iterations);
            }
        }
    }
}