package com.example.acousticcamera.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.acousticcamera.algorithm.FftUtils
import com.example.acousticcamera.data.AudioRepository
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

    fun runAnalysis() {
        viewModelScope.launch(Dispatchers.Default) {
            _statusText.value = "正在生成仿真数据..."

            // 1. 获取数据
            val audioData = AudioRepository.generateSimulationData()

            _statusText.value = "正在进行 FFT 计算..."
            // 2. 取第一个麦克风的数据做 FFT
            val mic0Data = audioData.data[0]
            val fftResult = FftUtils.computeMagnitudeSpectrum(mic0Data)

            // 3. 更新 UI
            _spectrumData.value = fftResult
            _statusText.value = "分析完成！数据长度: ${fftResult.size}"
        }
    }
}