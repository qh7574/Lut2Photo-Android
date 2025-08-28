package cn.alittlecookie.lut2photo.lut2photo

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import cn.alittlecookie.lut2photo.lut2photo.benchmark.PerformanceBenchmark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 性能基准测试Activity
 * 用于在实际Android环境中测试native代码性能
 */
class BenchmarkActivity : Activity() {

    companion object {
        private const val TAG = "BenchmarkActivity"
    }

    private lateinit var resultTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 创建简单的布局
        resultTextView = TextView(this).apply {
            text = "正在运行性能基准测试...\n请稍候..."
            textSize = 12f
            setPadding(16, 16, 16, 16)
        }

        setContentView(resultTextView)

        // 在后台线程运行基准测试
        runBenchmarkTest()
    }

    private fun runBenchmarkTest() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                resultTextView.text = "正在初始化性能基准测试...\n"

                val results = withContext(Dispatchers.Default) {
                    PerformanceBenchmark.runFullBenchmark()
                }

                // 显示结果
                resultTextView.text = results
                Log.i(TAG, "基准测试完成:\n$results")

            } catch (e: Exception) {
                val errorMessage = "基准测试失败: ${e.message}\n${e.stackTrace.joinToString("\n")}"
                resultTextView.text = errorMessage
                Log.e(TAG, "基准测试异常", e)
            }
        }
    }
}