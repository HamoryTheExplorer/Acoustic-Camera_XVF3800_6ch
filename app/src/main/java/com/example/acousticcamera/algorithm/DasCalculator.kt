package com.example.acousticcamera.algorithm

/**
 * DAS 计算器
 * 为了性能，使用简单的整数延迟 (Integer Delay)
 * 为了避免计算量过大，截取一小段窗口音频来分析
 */

import com.example.acousticcamera.data.AudioData
import com.example.acousticcamera.data.GridConfig
import com.example.acousticcamera.data.MicArrayConfig
import com.example.acousticcamera.data.Point3D
import kotlin.math.sqrt

object DasCalculator {

    private const val SPEED_OF_SOUND = 340.0f // 声速 m/s

    /**
     * 核心函数：计算声压热力图
     * 输入：多通道音频数据
     * 输出：一个一维数组 (长度 = 50*50)，表示每个格子的能量值
     */
    fun computeHeatmap(audioData: AudioData): FloatArray {
        val gridSize = GridConfig.GRID_SIZE
        val resultMap = FloatArray(gridSize * gridSize)
        val mics = MicArrayConfig.mics
        val sampleRate = audioData.sampleRate

        // 为了避免计算量太大，我们不分析整段音频
        // 只截取中间的一小段窗口来分析 (例如 1024 个采样点)
        val windowSize = 1024
        val centerIndex = audioData.data[0].size / 2
        val startIndex = centerIndex - windowSize / 2

        // --- 开始遍历每一个格子 (假设声源在这里) ---
        for (gy in 0 until gridSize) {
            for (gx in 0 until gridSize) {

                // 1. 获取当前格子的空间坐标
                val targetPoint = GridConfig.getPoint3D(gx, gy)

                // 用于累加所有麦克风对齐后的信号
                // 我们创建一个临时的缓冲区来存 "叠加后" 的波形
                val summedSignal = FloatArray(windowSize) { 0f }

                // 2. 遍历每一个麦克风
                for ((micIndex, micPos) in mics.withIndex()) {
                    // A. 计算距离 (目标点 <-> 麦克风)
                    val distance = MicArrayConfig.distance(targetPoint, micPos)

                    // B. 计算传播时间 (秒)
                    val travelTime = distance / SPEED_OF_SOUND

                    // C. 转换为样本数延迟 (Delay Samples)
                    // 距离越远，声音到得越晚。
                    // 为了"对齐"，我们需要把这个麦克风的数据向"左"移 (读取更早的数据)
                    // 或者理解为：我们需要读取 (t + delay) 时刻的数据来补偿
                    val delaySamples = (travelTime * sampleRate).toInt()

                    // D. 叠加波形 (Beamforming)
                    val micData = audioData.data[micIndex]

                    for (i in 0 until windowSize) {
                        // 原始流中的真实索引位置
                        // 我们想要 analyze 的时刻是 (startIndex + i)
                        // 加上 delaySamples 是为了补偿传播时间
                        val readIndex = startIndex + i + delaySamples

                        // 边界检查，防止数组越界
                        if (readIndex >= 0 && readIndex < micData.size) {
                            summedSignal[i] += micData[readIndex]
                        }
                    }
                }

                // 3. 计算该格子的能量 (Energy)
                // 叠加完后，算出这个 summedSignal 的能量 (RMS 或 平均绝对值)
                var energy = 0.0f
                for (sample in summedSignal) {
                    energy += sample * sample // 平方求和
                }

                // 存入结果数组 (一维索引)
                // 注意：这里 energy 是所有麦克风能量的总和。
                // 如果对齐了，energy 会非常大 (N^2)；如果没对齐，energy 比较小 (N)。
                resultMap[gy * gridSize + gx] = energy
            }
        }

        // 4. 可选：简单的归一化 (让最大值为 1.0)
        val maxVal = resultMap.maxOrNull() ?: 1f
        if (maxVal > 0) {
            for (i in resultMap.indices) {
                resultMap[i] /= maxVal
            }
        }

        return resultMap
    }
}