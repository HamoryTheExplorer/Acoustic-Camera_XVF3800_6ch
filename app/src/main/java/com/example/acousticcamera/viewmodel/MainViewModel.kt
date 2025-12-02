package com.example.acousticcamera.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.acousticcamera.algorithm.DasCalculator
import com.example.acousticcamera.algorithm.FftUtils
import com.example.acousticcamera.data.AudioRepository
import com.example.acousticcamera.data.Point3D
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {

    // 状态：当前显示的频谱数据（注意是否只存第0个通道用于测试）
    private val _spectrumData = MutableStateFlow<FloatArray?>(null)
    val spectrumData = _spectrumData.asStateFlow()

    private val _statusText = MutableStateFlow("点击按钮开始分析")
    val statusText = _statusText.asStateFlow()

    // 新增状态：热力图数据
    private val _heatmapData = MutableStateFlow<FloatArray?>(null)
    val heatmapData = _heatmapData.asStateFlow()

    fun runAnalysis() {
        viewModelScope.launch(Dispatchers.Default) {
            _statusText.value = "1. 生成仿真数据..."

            // 声源设在 (0.5, 0.5) 处，对应热力图的 右上方 区域
            val sourcePos = Point3D(0.5f, 0.5f, 1.0f)
            val audioData = AudioRepository.generateSimulationData(sourcePos)

            _statusText.value = "2. 计算 FFT..."
            val mic0Data = audioData.data[0]
            val fftResult = FftUtils.computeMagnitudeSpectrum(mic0Data)
            _spectrumData.value = fftResult

            // --- 新增：DAS 计算步骤 ---
            _statusText.value = "3. 执行 DAS 波束形成 (计算量大)..."

            val startTime = System.currentTimeMillis()
            // 调用算法
            val heatmap = DasCalculator.computeHeatmap(audioData)
            val costTime = System.currentTimeMillis() - startTime

            _heatmapData.value = heatmap

            _statusText.value = "分析完成! 耗时: ${costTime}ms \n声源位置:(${sourcePos.x}, ${sourcePos.y})"
        }
    }

    // 重置功能
    fun resetAnalysis() {
        // 设为 null，UI 层会自动将其转换为全 0 的数组显示空坐标轴
        _spectrumData.value = null
        _statusText.value = "已重置，点击按钮开始分析"
    }
}