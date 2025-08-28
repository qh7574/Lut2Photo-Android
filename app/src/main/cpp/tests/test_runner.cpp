#include "performance_test.h"
#include <iostream>
#include <string>
#include <vector>
#include <chrono>

#ifdef __ANDROID__

#include <android/log.h>

#define LOG_TAG "TestRunner"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#include <iostream>
#define LOGI(...) printf(__VA_ARGS__); printf("\n")
#define LOGE(...) fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n")
#endif

// 测试运行器类
class TestRunner {
public:
    TestRunner() {
        testSuite_ = std::make_unique<PerformanceTestSuite>();
    }

    ~TestRunner() = default;

    // 运行所有测试
    void runAllTests() {
        LOGI("=== 开始运行完整性能测试套件 ===");

        auto startTime = std::chrono::high_resolution_clock::now();

        auto results = testSuite_->runAllTests();

        auto endTime = std::chrono::high_resolution_clock::now();
        auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - startTime);

        LOGI("\n=== 测试完成，总耗时: %lld ms ===", duration.count());

        // 打印结果摘要
        testSuite_->printSummary(results);

        // 生成报告
        generateReports(results, "performance_test_full");

        // 分析结果
        analyzeResults(results);
    }

    // 运行快速测试
    void runQuickTests() {
        LOGI("=== 开始运行快速性能测试 ===");

        // 设置快速测试配置
        auto quickConfig = PerformanceTestUtils::createQuickTestConfig();
        testSuite_->setTestConfig(quickConfig);

        auto startTime = std::chrono::high_resolution_clock::now();

        // 运行关键测试
        std::vector<PerformanceResult> results;
        results.push_back(testSuite_->testMemoryAllocation());
        results.push_back(testSuite_->testMemoryPoolPerformance());
        results.push_back(testSuite_->testLutProcessingPerformance());
        results.push_back(testSuite_->testStreamingProcessorPerformance());

        auto endTime = std::chrono::high_resolution_clock::now();
        auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - startTime);

        LOGI("\n=== 快速测试完成，总耗时: %lld ms ===", duration.count());

        // 打印结果
        PerformanceTestUtils::printResultTable(results);

        // 生成报告
        generateReports(results, "performance_test_quick");
    }

    // 运行内存测试
    void runMemoryTests() {
        LOGI("=== 开始运行内存性能测试 ===");

        auto startTime = std::chrono::high_resolution_clock::now();

        auto results = testSuite_->runMemoryTests();

        auto endTime = std::chrono::high_resolution_clock::now();
        auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - startTime);

        LOGI("\n=== 内存测试完成，总耗时: %lld ms ===", duration.count());

        // 打印结果
        testSuite_->printSummary(results);

        // 生成报告
        generateReports(results, "performance_test_memory");

        // 内存测试特定分析
        analyzeMemoryResults(results);
    }

    // 运行处理测试
    void runProcessingTests() {
        LOGI("=== 开始运行处理性能测试 ===");

        auto startTime = std::chrono::high_resolution_clock::now();

        auto results = testSuite_->runProcessingTests();

        auto endTime = std::chrono::high_resolution_clock::now();
        auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - startTime);

        LOGI("\n=== 处理测试完成，总耗时: %lld ms ===", duration.count());

        // 打印结果
        testSuite_->printSummary(results);

        // 生成报告
        generateReports(results, "performance_test_processing");

        // 处理测试特定分析
        analyzeProcessingResults(results);
    }

    // 运行压力测试
    void runStressTests() {
        LOGI("=== 开始运行压力测试 ===");

        // 设置压力测试配置
        auto stressConfig = PerformanceTestUtils::createStressTestConfig();
        testSuite_->setTestConfig(stressConfig);

        auto startTime = std::chrono::high_resolution_clock::now();

        // 运行压力测试
        std::vector<PerformanceResult> results;
        results.push_back(testSuite_->testMemoryPressureHandling());
        results.push_back(testSuite_->testLargeImageProcessing());
        results.push_back(testSuite_->testMultiThreadedProcessing());
        results.push_back(testSuite_->testConcurrentMemoryAccess());

        auto endTime = std::chrono::high_resolution_clock::now();
        auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - startTime);

        LOGI("\n=== 压力测试完成，总耗时: %lld ms ===", duration.count());

        // 打印结果
        testSuite_->printSummary(results);

        // 生成报告
        generateReports(results, "performance_test_stress");

        // 压力测试特定分析
        analyzeStressResults(results);
    }

    // 运行回归测试
    void runRegressionTests(const std::string &baselineFile) {
        LOGI("=== 开始运行回归测试 ===");

        // 加载基线结果
        auto baselineResults = PerformanceTestUtils::loadResultsFromFile(baselineFile);
        if (baselineResults.empty()) {
            LOGE("无法加载基线测试结果: %s", baselineFile.c_str());
            return;
        }

        // 运行当前测试
        auto currentResults = testSuite_->runAllTests();

        // 比较结果
        bool passed = testSuite_->validatePerformanceRegression(currentResults, baselineResults,
                                                                10.0);

        LOGI("\n=== 回归测试结果: %s ===", passed ? "通过" : "失败");

        // 详细比较
        compareWithBaseline(currentResults, baselineResults);

        // 生成回归测试报告
        generateRegressionReport(currentResults, baselineResults, "performance_regression_test");
    }

private:
    std::unique_ptr<PerformanceTestSuite> testSuite_;

    // 生成报告
    void
    generateReports(const std::vector<PerformanceResult> &results, const std::string &baseName) {
        std::string outputPath = "/sdcard/Android/data/com.example.lut2photo/files/" + baseName;

#ifndef __ANDROID__
        outputPath = "./" + baseName;
#endif

        testSuite_->generateReport(results, outputPath);
        LOGI("测试报告已生成: %s", outputPath.c_str());
    }

    // 分析测试结果
    void analyzeResults(const std::vector<PerformanceResult> &results) {
        LOGI("\n=== 性能分析 ===");

        // 找出最慢的测试
        auto slowestTest = std::max_element(results.begin(), results.end(),
                                            [](const PerformanceResult &a,
                                               const PerformanceResult &b) {
                                                return a.averageTimeMs < b.averageTimeMs;
                                            });

        if (slowestTest != results.end()) {
            LOGI("最慢的测试: %s (%.2f ms)", slowestTest->testName.c_str(),
                 slowestTest->averageTimeMs);
        }

        // 找出最快的测试
        auto fastestTest = std::min_element(results.begin(), results.end(),
                                            [](const PerformanceResult &a,
                                               const PerformanceResult &b) {
                                                return a.averageTimeMs < b.averageTimeMs;
                                            });

        if (fastestTest != results.end()) {
            LOGI("最快的测试: %s (%.2f ms)", fastestTest->testName.c_str(),
                 fastestTest->averageTimeMs);
        }

        // 计算总体统计
        double totalTime = 0.0;
        double totalVariance = 0.0;
        size_t validTests = 0;

        for (const auto &result: results) {
            if (result.isValid()) {
                totalTime += result.averageTimeMs;
                totalVariance += result.standardDeviation * result.standardDeviation;
                validTests++;
            }
        }

        if (validTests > 0) {
            double averageTime = totalTime / validTests;
            double averageStdDev = std::sqrt(totalVariance / validTests);

            LOGI("平均测试时间: %.2f ms", averageTime);
            LOGI("平均标准差: %.2f ms", averageStdDev);
        }

        // 检查异常结果
        checkAnomalousResults(results);
    }

    // 分析内存测试结果
    void analyzeMemoryResults(const std::vector<PerformanceResult> &results) {
        LOGI("\n=== 内存性能分析 ===");

        size_t totalPeakMemory = 0;
        size_t totalAverageMemory = 0;
        size_t totalLeaks = 0;
        size_t validTests = 0;

        for (const auto &result: results) {
            if (result.isValid()) {
                totalPeakMemory += result.peakMemoryUsage;
                totalAverageMemory += result.averageMemoryUsage;
                totalLeaks += result.memoryLeaks;
                validTests++;

                if (result.memoryLeaks > 0) {
                    LOGI("检测到内存泄漏: %s (%zu bytes)", result.testName.c_str(),
                         result.memoryLeaks);
                }
            }
        }

        if (validTests > 0) {
            LOGI("平均峰值内存使用: %zu bytes", totalPeakMemory / validTests);
            LOGI("平均内存使用: %zu bytes", totalAverageMemory / validTests);
            LOGI("总内存泄漏: %zu bytes", totalLeaks);
        }
    }

    // 分析处理测试结果
    void analyzeProcessingResults(const std::vector<PerformanceResult> &results) {
        LOGI("\n=== 处理性能分析 ===");

        for (const auto &result: results) {
            if (result.isValid()) {
                double throughput = 1000.0 / result.averageTimeMs; // 每秒处理次数
                LOGI("%s: %.2f 处理/秒", result.testName.c_str(), throughput);

                if (result.successRate < 95.0) {
                    LOGI("警告: %s 成功率较低 (%.1f%%)", result.testName.c_str(),
                         result.successRate);
                }
            }
        }
    }

    // 分析压力测试结果
    void analyzeStressResults(const std::vector<PerformanceResult> &results) {
        LOGI("\n=== 压力测试分析 ===");

        for (const auto &result: results) {
            if (result.isValid()) {
                // 检查性能稳定性
                double variabilityRatio = result.standardDeviation / result.averageTimeMs;
                if (variabilityRatio > 0.3) {
                    LOGI("警告: %s 性能变化较大 (变异系数: %.2f)",
                         result.testName.c_str(), variabilityRatio);
                }

                // 检查极端值
                double extremeRatio = result.maxTimeMs / result.minTimeMs;
                if (extremeRatio > 5.0) {
                    LOGI("警告: %s 存在极端性能差异 (最大/最小比率: %.2f)",
                         result.testName.c_str(), extremeRatio);
                }
            }
        }
    }

    // 检查异常结果
    void checkAnomalousResults(const std::vector<PerformanceResult> &results) {
        LOGI("\n=== 异常检测 ===");

        for (const auto &result: results) {
            if (!result.isValid()) {
                LOGI("无效测试结果: %s", result.testName.c_str());
                continue;
            }

            // 检查成功率
            if (result.successRate < 90.0) {
                LOGI("低成功率: %s (%.1f%%)", result.testName.c_str(), result.successRate);
            }

            // 检查性能异常
            if (result.averageTimeMs > 1000.0) {
                LOGI("性能较慢: %s (%.2f ms)", result.testName.c_str(), result.averageTimeMs);
            }

            // 检查内存使用异常
            if (result.peakMemoryUsage > 100 * 1024 * 1024) { // 100MB
                LOGI("高内存使用: %s (%zu bytes)", result.testName.c_str(), result.peakMemoryUsage);
            }
        }
    }

    // 与基线比较
    void compareWithBaseline(const std::vector<PerformanceResult> &current,
                             const std::vector<PerformanceResult> &baseline) {
        LOGI("\n=== 基线比较 ===");

        for (const auto &currentResult: current) {
            auto baselineIt = std::find_if(baseline.begin(), baseline.end(),
                                           [&currentResult](const PerformanceResult &baseline) {
                                               return baseline.testName == currentResult.testName;
                                           });

            if (baselineIt != baseline.end()) {
                double improvement = PerformanceTestUtils::calculatePerformanceImprovement(
                        *baselineIt, currentResult);

                if (improvement > 5.0) {
                    LOGI("%s: 性能提升 %.1f%%", currentResult.testName.c_str(), improvement);
                } else if (improvement < -5.0) {
                    LOGI("%s: 性能下降 %.1f%%", currentResult.testName.c_str(), -improvement);
                } else {
                    LOGI("%s: 性能基本持平 (%.1f%%)", currentResult.testName.c_str(), improvement);
                }
            }
        }
    }

    // 生成回归测试报告
    void generateRegressionReport(const std::vector<PerformanceResult> &current,
                                  const std::vector<PerformanceResult> &baseline,
                                  const std::string &baseName) {
        std::string outputPath =
                "/sdcard/Android/data/com.example.lut2photo/files/" + baseName + ".html";

#ifndef __ANDROID__
        outputPath = "./" + baseName + ".html";
#endif

        std::ofstream file(outputPath);
        if (!file.is_open()) {
            LOGE("无法创建回归测试报告: %s", outputPath.c_str());
            return;
        }

        file << "<!DOCTYPE html>\n<html>\n<head>\n";
        file << "<title>回归测试报告</title>\n";
        file << "<style>\n";
        file << "body { font-family: Arial, sans-serif; margin: 20px; }\n";
        file << "table { border-collapse: collapse; width: 100%; }\n";
        file << "th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }\n";
        file << "th { background-color: #f2f2f2; }\n";
        file << ".improvement { color: green; }\n";
        file << ".regression { color: red; }\n";
        file << "</style>\n";
        file << "</head>\n<body>\n";
        file << "<h1>回归测试报告</h1>\n";

        file << "<table>\n";
        file
                << "<tr><th>测试名称</th><th>当前时间(ms)</th><th>基线时间(ms)</th><th>变化(%)</th><th>状态</th></tr>\n";

        for (const auto &currentResult: current) {
            auto baselineIt = std::find_if(baseline.begin(), baseline.end(),
                                           [&currentResult](const PerformanceResult &baseline) {
                                               return baseline.testName == currentResult.testName;
                                           });

            if (baselineIt != baseline.end()) {
                double improvement = PerformanceTestUtils::calculatePerformanceImprovement(
                        *baselineIt, currentResult);

                file << "<tr>";
                file << "<td>" << currentResult.testName << "</td>";
                file << "<td>" << std::fixed << std::setprecision(2) << currentResult.averageTimeMs
                     << "</td>";
                file << "<td>" << std::fixed << std::setprecision(2) << baselineIt->averageTimeMs
                     << "</td>";

                std::string cssClass = "";
                std::string status = "";
                if (improvement > 5.0) {
                    cssClass = "improvement";
                    status = "提升";
                } else if (improvement < -5.0) {
                    cssClass = "regression";
                    status = "下降";
                } else {
                    status = "持平";
                }

                file << "<td class=\"" << cssClass << "\">" << std::fixed << std::setprecision(1)
                     << improvement << "</td>";
                file << "<td class=\"" << cssClass << "\">" << status << "</td>";
                file << "</tr>\n";
            }
        }

        file << "</table>\n";
        file << "</body>\n</html>\n";

        LOGI("回归测试报告已生成: %s", outputPath.c_str());
    }
};

// 主函数 - 用于独立测试
#ifndef __ANDROID__
int main(int argc, char* argv[]) {
    TestRunner runner;
    
    if (argc > 1) {
        std::string testType = argv[1];
        
        if (testType == "all") {
            runner.runAllTests();
        } else if (testType == "quick") {
            runner.runQuickTests();
        } else if (testType == "memory") {
            runner.runMemoryTests();
        } else if (testType == "processing") {
            runner.runProcessingTests();
        } else if (testType == "stress") {
            runner.runStressTests();
        } else if (testType == "regression" && argc > 2) {
            runner.runRegressionTests(argv[2]);
        } else {
            std::cout << "用法: " << argv[0] << " [all|quick|memory|processing|stress|regression <baseline_file>]" << std::endl;
            return 1;
        }
    } else {
        // 默认运行快速测试
        runner.runQuickTests();
    }
    
    return 0;
}
#endif

// Android JNI 接口
#ifdef __ANDROID__
extern "C" {
// 运行所有测试
JNIEXPORT void JNICALL
Java_com_example_lut2photo_PerformanceTestRunner_runAllTests(JNIEnv * env , jobject thiz ) {
TestRunner runner;
runner . runAllTests();
}

// 运行快速测试
JNIEXPORT void JNICALL
Java_com_example_lut2photo_PerformanceTestRunner_runQuickTests(JNIEnv
*env,
jobject thiz
) {
TestRunner runner;
runner.

runQuickTests();

}

// 运行内存测试
JNIEXPORT void JNICALL
Java_com_example_lut2photo_PerformanceTestRunner_runMemoryTests(JNIEnv
*env,
jobject thiz
) {
TestRunner runner;
runner.

runMemoryTests();

}

// 运行处理测试
JNIEXPORT void JNICALL
Java_com_example_lut2photo_PerformanceTestRunner_runProcessingTests(JNIEnv
*env,
jobject thiz
) {
TestRunner runner;
runner.

runProcessingTests();

}

// 运行压力测试
JNIEXPORT void JNICALL
Java_com_example_lut2photo_PerformanceTestRunner_runStressTests(JNIEnv
*env,
jobject thiz
) {
TestRunner runner;
runner.

runStressTests();

}
}
#endif