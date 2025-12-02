package com.example.acousticcamera.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.acousticcamera.algorithm.FftUtils
import com.example.acousticcamera.data.AudioRepository
import com.example.acousticcamera.data.Point3D
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {

    // 状态：当前显示的频谱数据（这里只存第0个通道用于测试）
    private val _spectrumData = MutableStateFlow<FloatArray?>(null)
    val spectrumData = _spectrumData.asStateFlow()

    private val _statusText = MutableStateFlow("点击按钮开始分析")
    val statusText = _statusText.asStateFlow()

    // ...
    fun runAnalysis() {
        viewModelScope.launch(Dispatchers.Default) {
            _statusText.value = "正在生成 64 通道仿真数据..."

            // 设定一个明显的声源位置 (偏右侧，这样左边和右边的麦克风延迟差大)
            val sourcePos = Point3D(1.0f, 0.0f, 1.0f)
            val audioData = AudioRepository.generateSimulationData(sourcePos)

            _statusText.value = "数据生成完毕，正在计算 FFT..."

            // 这里我们只显示第 0 号麦克风 (左上角) 的频谱
            // 真正的声学相机后面会用到所有通道的数据
            val mic0Data = audioData.data[0]
            val fftResult = FftUtils.computeMagnitudeSpectrum(mic0Data)

            _spectrumData.value = fftResult

            // 更新提示文字，告诉自己这是多少个通道的数据
            _statusText.value = "通道数:${audioData.channels}  声源模拟位置:(${sourcePos.x}, ${sourcePos.y})"
        }
    }

    // 新增：重置功能
    fun resetAnalysis() {
        // 设为 null，UI 层会自动将其转换为全 0 的数组显示空坐标轴
        _spectrumData.value = null
        _statusText.value = "已重置，点击按钮开始分析"
    }
}