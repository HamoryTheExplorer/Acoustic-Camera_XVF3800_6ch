package com.example.acousticcamera.viewmodel

import android.app.Application
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.acousticcamera.algorithm.DasCalculatorTurbo
import com.example.acousticcamera.data.AudioSource
import com.example.acousticcamera.data.AudioSourceMode
import com.example.acousticcamera.data.HardwareAudioSource
import com.example.acousticcamera.data.MicDeviceInfo
import com.example.acousticcamera.data.SimulationAudioSource
import com.example.acousticcamera.data.UsbAudioSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * 状态层
 * 持有数据 state，处理点击事件，支持仿真/硬件两种音频数据源切换。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val audioManager =
        application.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager

    // ─── 麦克风设备选择 ────────────────────────────────────────────────────
    private val _availableMicDevices = MutableStateFlow<List<MicDeviceInfo>>(emptyList())
    val availableMicDevices = _availableMicDevices.asStateFlow()

    // null = 系统默认，不调用 setPreferredDevice
    private val _selectedMicDeviceId = MutableStateFlow<Int?>(null)
    val selectedMicDeviceId = _selectedMicDeviceId.asStateFlow()

    // ─── 数据源选择 ────────────────────────────────────────────────────────
    private val _sourceMode = MutableStateFlow(AudioSourceMode.SIMULATION)
    val sourceMode = _sourceMode.asStateFlow()

    private var currentAudioSource: AudioSource = SimulationAudioSource()

    /** XVF3800 直连音频源引用（用于模式切换时精确释放 USB 资源） */
    private var usbAudioSource: UsbAudioSource? = null

    // ─── UI 状态 ───────────────────────────────────────────────────────────
    private val _spectrumData = MutableStateFlow<FloatArray?>(null)
    val spectrumData = _spectrumData.asStateFlow()

    private val _heatmapData = MutableStateFlow<FloatArray?>(null)
    val heatmapData = _heatmapData.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.asStateFlow()

    private val _statusText = MutableStateFlow("点击按钮开始分析")
    val statusText = _statusText.asStateFlow()

    private val _fps = MutableStateFlow("0 FPS")
    val fps = _fps.asStateFlow()

    // 错误提示（硬件初始化失败等）
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    private var analysisJob: Job? = null
    private var lastFrameTime = 0L
    private var lastFpsEmitTime = 0L

    // ─── 频谱平滑 ────────────────────────────────────────────────────────────
    // 指数移动平均 (EMA) 平滑因子：值越小越平滑（但响应越慢）
    private var smoothedSpectrum: FloatArray? = null
    private val spectrumSmoothAlpha = 0.20f

    // ─── 数据源切换 ────────────────────────────────────────────────────────

    /**
     * 切换数据源模式（仿真 ↔ 硬件 ↔ XVF3800）。
     *
     * 切换顺序（关键）：
     *   1. release() 先停止底层 AudioRecord，让阻塞在 read() 中的线程立即返回
     *   2. cancel()  + join()   等待协程 finally 块完成清理
     *   3. reset()  + 新数据源   清除 DAS 缓冲区并创建新模式实例
     *
     * 若先 cancel/join 再 release，join() 会因 AudioRecord.read() 阻塞而长时间挂起。
     */
    fun setSourceMode(mode: AudioSourceMode) {
        if (_sourceMode.value == mode) return

        val wasRunning = _isRunning.value
        val oldJob = analysisJob
        analysisJob = null

        // 立即更新 UI 状态，让按钮选中态即时响应
        _sourceMode.value = mode
        _isRunning.value = false

        // 1. 先停止硬件：让阻塞在 read() 中的线程立即返回
        if (oldJob != null) {
            currentAudioSource.release()
        }
        // 2. 发送取消信号
        oldJob?.cancel()

        viewModelScope.launch {
            // 3. 等待旧协程 finally 块完成（此时 read() 已解除阻塞，join 很快完成）
            oldJob?.join()

            // 4. 重置算法状态并替换数据源
            //    reset() 必须在 release() 之后、新数据源建立之前调用，
            //    否则新模式的 nMics/sampleRate 与旧缓冲区尺寸不匹配会导致 AIOOBE
            DasCalculatorTurbo.reset()

            // 切换离开 XVF3800 时释放 USB 资源
            if (mode != AudioSourceMode.XVF3800) {
                usbAudioSource?.release()
                usbAudioSource = null
            }

            currentAudioSource = when (mode) {
                AudioSourceMode.SIMULATION -> SimulationAudioSource()
                AudioSourceMode.HARDWARE   -> HardwareAudioSource(_selectedMicDeviceId.value, getApplication())
                AudioSourceMode.XVF3800    -> {
                    usbAudioSource?.release()  // 防御：释放可能残留的旧实例
                    UsbAudioSource(getApplication()).also { usbAudioSource = it }
                }
            }

            // 清空上一个模式遗留的图像数据
            smoothedSpectrum = null
            _spectrumData.value = null
            _heatmapData.value  = null

            // 切换模式后将分析停止，通知用户手动重新开始
            val restartHint = if (wasRunning) "，分析已停止，请重新开始" else "，点击开始分析"
            _statusText.value = when (mode) {
                AudioSourceMode.SIMULATION -> "已切换为仿真模式$restartHint"
                AudioSourceMode.HARDWARE   -> "已切换为硬件模式（标准2通道）$restartHint\n⚠ 若连接了XVF3800，请使用XVF3800模式获取原始麦克风数据"
                AudioSourceMode.XVF3800    -> "已切换为XVF3800模式（USB 6通道）$restartHint"
            }
        }
    }

    // ─── 麦克风设备管理 ────────────────────────────────────────────────────

    fun refreshMicDevices() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        _availableMicDevices.value = devices.map { d ->
            MicDeviceInfo(
                id        = d.id,
                name      = d.productName?.toString()?.ifEmpty { "麦克风 #${d.id}" } ?: "麦克风 #${d.id}",
                typeLabel = micTypeLabel(d.type)
            )
        }
    }

    /**
     * 切换所用麦克风设备。
     * 若当前处于硬件/XVF3800 模式，停止分析并重建音频源；仿真模式则仅存储选择。
     *
     * 切换顺序：release() 停止硬件 → cancel() + join() 等待清理 → 创建新数据源。
     */
    fun setSelectedMicDevice(deviceId: Int?) {
        if (_selectedMicDeviceId.value == deviceId) return
        _selectedMicDeviceId.value = deviceId

        val currentMode = _sourceMode.value
        if (currentMode != AudioSourceMode.HARDWARE && currentMode != AudioSourceMode.XVF3800) return

        val oldJob = analysisJob
        analysisJob = null
        _isRunning.value = false

        if (oldJob != null) {
            currentAudioSource.release()  // 先停止硬件，解除 read() 阻塞
        }
        oldJob?.cancel()

        viewModelScope.launch {
            oldJob?.join()
            currentAudioSource = if (currentMode == AudioSourceMode.XVF3800) {
                usbAudioSource?.release()
                UsbAudioSource(getApplication()).also { usbAudioSource = it }
            } else {
                HardwareAudioSource(deviceId, getApplication())
            }
            _spectrumData.value = null
            _heatmapData.value  = null
            _statusText.value   = "已更换麦克风，点击开始分析"
        }
    }

    private fun micTypeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC   -> "内置麦克风"
        AudioDeviceInfo.TYPE_USB_DEVICE    -> "USB 设备"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙 SCO"
        22                                 -> "USB 耳机"   // TYPE_USB_HEADSET (API 26+)
        else                               -> "类型 $type"
    }

    // ─── 分析控制 ──────────────────────────────────────────────────────────

    fun startRealTimeAnalysis() {
        if (_isRunning.value) return

        _isRunning.value = true
        _errorMessage.value = null
        _statusText.value = "正在初始化..."
        lastFrameTime = 0L
        lastFpsEmitTime = 0L

        analysisJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                currentAudioSource.audioStream()
                    .cancellable()
                    .conflate()
                    .collect { chunkData ->
                        val startTime = System.currentTimeMillis()

                        // DAS 热力图（全通道）+ 信道 0 频谱（DAS 内部 FFT 顺带计算，无重复开销）
                        val result = DasCalculatorTurbo.computeHeatmap(chunkData)

                        // 频谱指数平滑：防止每帧原始 FFT 幅值跳变太快导致 UI 闪烁
                        val rawSpec = result.spectrum
                        if (smoothedSpectrum == null || smoothedSpectrum!!.size != rawSpec.size) {
                            smoothedSpectrum = rawSpec.copyOf()
                        } else {
                            val a = spectrumSmoothAlpha
                            val invA = 1f - a
                            val smoothBuf = smoothedSpectrum!!
                            for (i in rawSpec.indices) {
                                smoothBuf[i] = a * rawSpec[i] + invA * smoothBuf[i]
                            }
                        }
                        _spectrumData.value = smoothedSpectrum
                        _heatmapData.value  = result.heatmap

                        // FPS 计算（仅用于右上角显示，200ms 刷新一次，避免人眼不可读的闪烁）
                        val currentTime = System.currentTimeMillis()
                        if (lastFrameTime != 0L) {
                            val diff = currentTime - lastFrameTime
                            val fpsValue = if (diff > 0) 1000f / diff else 0f
                            if (currentTime - lastFpsEmitTime >= 200L) {
                                _fps.value = "FPS: %.1f".format(fpsValue)
                                lastFpsEmitTime = currentTime
                            }
                        }
                        lastFrameTime = currentTime

                        val processMs = currentTime - startTime
                        val modeLabel = when (_sourceMode.value) {
                            AudioSourceMode.SIMULATION -> "[仿真]"
                            AudioSourceMode.HARDWARE   -> "[硬件]"
                            AudioSourceMode.XVF3800    -> "[XVF3800]"
                        }
                        _statusText.value = "$modeLabel DAS_IN: rate=${chunkData.sampleRate} ch=${chunkData.channels} " +
                            "frameSize=${chunkData.data[0].size} listSize=${chunkData.data.size} | " +
                            "耗时: ${processMs}ms"
                    }
            } catch (e: CancellationException) {
                throw e  // 必须重新抛出，不能吞掉协程取消信号
            } catch (e: Exception) {
                // 硬件初始化失败（AudioRecord 构建失败）或其他运行时错误
                _errorMessage.value = e.message
                _statusText.value = "硬件初始化失败，请检查连接"
                _isRunning.value = false
            }
        }
    }

    fun stopAnalysis() {
        analysisJob?.cancel()
        _isRunning.value = false
        _statusText.value = "已停止"
    }

    fun resetAnalysis() {
        stopAnalysis()
        smoothedSpectrum = null
        _spectrumData.value = null
        _heatmapData.value = null
        _errorMessage.value = null
        _statusText.value = "已重置，点击按钮开始分析"
    }

    fun clearError() {
        _errorMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        currentAudioSource.release()
        usbAudioSource = null
    }
}
