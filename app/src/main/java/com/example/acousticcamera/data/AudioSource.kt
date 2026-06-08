package com.example.acousticcamera.data

import kotlinx.coroutines.flow.Flow

/**
 * 统一音频源接口
 * 仿真数据源（[SimulationAudioSource]）和硬件数据源（[HardwareAudioSource]）均实现此接口。
 */
interface AudioSource {

    /** 返回连续音频数据流，调用方取消协程即可停止采集 */
    fun audioStream(): Flow<AudioData>

    /**
     * 释放底层硬件资源（仅硬件数据源需要实现）。
     * 停止分析后由 ViewModel 调用。
     */
    fun release() {}
}

/** 数据源模式枚举，供 UI 选择 */
enum class AudioSourceMode {
    SIMULATION, // 仿真模式
    HARDWARE,   // 硬件模式（标准 2 通道，兼容模拟器）
    XVF3800     // XVF3800 模式（USB 6 通道，提取 Mic 2-5）
}
