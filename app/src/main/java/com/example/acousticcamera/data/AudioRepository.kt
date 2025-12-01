package com.example.acousticcamera.data

import kotlin.math.PI
import kotlin.math.sin

object AudioRepository {

    // 模拟生成一个 1000Hz 的正弦波
    fun generateSimulationData(): AudioData {
        val sampleRate = 44100
        val durationSeconds = 1
        val numSamples = sampleRate * durationSeconds
        val numChannels = 8 // 假设有8个麦克风

        val channelDataList = ArrayList<FloatArray>()

        // 为每个麦克风生成数据
        for (ch in 0 until numChannels) {
            val samples = FloatArray(numSamples)
            for (i in 0 until numSamples) {
                // 时间 t
                val t = i.toDouble() / sampleRate
                // 生成正弦波：sin(2πft)，稍微加一点点随机噪音模拟真实情况
                val noise = (Math.random() - 0.5) * 0.05
                val signal = sin(2 * PI * 1000 * t) + noise
                samples[i] = signal.toFloat()
            }
            channelDataList.add(samples)
        }

        return AudioData(
            sampleRate = sampleRate,
            channels = numChannels,
            data = channelDataList
        )
    }
}