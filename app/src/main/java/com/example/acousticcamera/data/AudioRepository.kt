package com.example.acousticcamera.data

import kotlin.math.PI
import kotlin.math.sin

/**
 * 生成仿真音频数据
 */

object AudioRepository {

    // 参数：simulatedSourcePos 是我们模拟的声源位置
    // 默认声源在：右上方 (0.5m, 0.5m)，距离阵列 1米处 (Z=1.0)
    fun generateSimulationData(
        simulatedSourcePos: Point3D = Point3D(0.5f, 0.5f, 1.0f)
    ): AudioData {
        val sampleRate = 44100
        val durationSeconds = 0.1 // 缩短一点，0.1秒足够分析了，计算也快
        val numSamples = (sampleRate * durationSeconds).toInt()

        // 引用我们定义的麦克风阵列
        val mics = MicArrayConfig.mics
        val channelDataList = ArrayList<FloatArray>()

        val speedOfSound = 340.0 // 声速 m/s
        val frequency = 2000.0   // 频率 2000Hz (波长约17cm)

        // 对每个麦克风进行循环
        for (mic in mics) {
            val samples = FloatArray(numSamples)

            // 1. 计算 声源 到 当前麦克风 的距离
            val dist = MicArrayConfig.distance(mic, simulatedSourcePos)

            // 2. 计算传播时间 (延迟) = 距离 / 声速
            val delaySeconds = dist / speedOfSound

            for (i in 0 until numSamples) {
                // 当前时刻 t
                val t = i.toDouble() / sampleRate

                // 3. 核心物理公式：sin( 2πf * (t - delay) )
                // 这里的 (t - delaySeconds) 就是相移的关键！
                val timeWithDelay = t - delaySeconds

                // 简单的白噪声 (模拟底噪)
                val noise = (Math.random() - 0.5) * 0.02

                // 生成信号
                val signal = sin(2 * PI * frequency * timeWithDelay) + noise

                samples[i] = signal.toFloat()
            }
            channelDataList.add(samples)
        }

        return AudioData(
            sampleRate = sampleRate,
            channels = mics.size,
            data = channelDataList
        )
    }
}