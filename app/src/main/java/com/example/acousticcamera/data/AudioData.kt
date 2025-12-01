package com.example.acousticcamera.data

data class AudioData(
    val sampleRate: Int,      // 采样率，比如 44100
    val channels: Int,        // 麦克风数量，比如 64
    val data: List<FloatArray> // 核心数据：每个麦克风对应一个数组
)