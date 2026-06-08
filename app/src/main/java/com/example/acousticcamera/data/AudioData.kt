package com.example.acousticcamera.data

data class AudioData(
    val sampleRate: Int,       // 采样率 (Hz)
    val channels: Int,         // 麦克风通道数
    val data: List<FloatArray> // 每个麦克风对应一个数组
)