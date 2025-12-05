package com.example.acousticcamera.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.acousticcamera.algorithm.DasCalculator
import com.example.acousticcamera.algorithm.FftUtils
import com.example.acousticcamera.data.AudioRepository
import com.example.acousticcamera.data.Point3D
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.launch

/**
 * 状态层
 * 持有数据 state，处理点击事件
 */

class MainViewModel : ViewModel() {

    // 状态：当前显示的频谱数据（注意是否只存第0个通道用于测试）
    private val _spectrumData = MutableStateFlow<FloatArray?>(null)
    val spectrumData = _spectrumData.asStateFlow()

    private val _statusText = MutableStateFlow("点击按钮开始分析")
    val statusText = _statusText.asStateFlow()

    // 热力图数据状态
    private val _heatmapData = MutableStateFlow<FloatArray?>(null)
    val heatmapData = _heatmapData.asStateFlow()

    // 用于标记是否正在运行，控制 UI 按钮显示 "停止" 还是 "开始"
    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.asStateFlow()

    // 持有当前的仿真任务，以便随时取消(Stop)
    private var analysisJob: Job? = null

    // FPS 状态
    private val _fps = MutableStateFlow("0 FPS")
    val fps = _fps.asStateFlow()

    // 用于计算 FPS 的时间戳
    private var lastFrameTime = 0L

    /**
     * 启动实时仿真
     */
    fun startRealTimeAnalysis() {
        if (_isRunning.value) return // 防止重复点击

        _isRunning.value = true
        _statusText.value = "正在初始化实时流..."

        lastFrameTime = 0L // 重置时间戳

        // 启动一个协程 Job
        analysisJob = viewModelScope.launch(Dispatchers.Default) {
            // 收集 Flow
            AudioRepository.simulateContinuousData()
                .cancellable() // 允许被 cancel
                .collect { chunkData ->
                    // --- 性能计时开始 ---
                    val startTime = System.currentTimeMillis()

                    // 1. FFT (取第0个通道) TODO()
                    val fftResult = FftUtils.computeMagnitudeSpectrum(chunkData.data[0])
                    _spectrumData.value = fftResult

                    // 2. DAS 热力图
                    val heatmap = DasCalculator.computeHeatmap(chunkData)
                    _heatmapData.value = heatmap

                    // 3. 计算 FPS
                    val currentTime = System.currentTimeMillis()
                    if (lastFrameTime != 0L) {
                        val diff = currentTime - lastFrameTime
                        if (diff > 0) {
                            // 瞬时 FPS = 1000 / 间隔毫秒
                            val currentFps = 1000f / diff
                            // 格式化保留1位小数
                            _fps.value = "FPS: %.1f".format(currentFps)
                        }
                    }
                    // --- 性能计时结束 ---
                    lastFrameTime = currentTime

                    // 计算单纯的算法耗时 (Process Time)
                    val processTime = currentTime - startTime
                    // 4. 更新状态文字
                    _statusText.value = "算法耗时: ${processTime}ms | 采样点数: ${chunkData.data[0].size}"
                }
        }
    }


    /**
     * 停止仿真
     */
    fun stopAnalysis() {
        analysisJob?.cancel() // 取消协程，Repo 里的 while(true) 也会停止
        _isRunning.value = false
        _statusText.value = "已停止"
    }

    // 重置功能
    fun resetAnalysis() {
        stopAnalysis()
        // 设为 null，UI 层会自动将其转换为全 0 的数组显示空坐标轴
        _spectrumData.value = null
        _heatmapData.value = null
        _statusText.value = "已重置，点击按钮开始分析"
    }
}