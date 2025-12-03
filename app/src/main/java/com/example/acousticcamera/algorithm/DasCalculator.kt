package com.example.acousticcamera.algorithm

import com.example.acousticcamera.data.AudioData
import com.example.acousticcamera.data.GridConfig
import com.example.acousticcamera.data.MicArrayConfig
import com.example.acousticcamera.data.Point3D
import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.*

/**
 * 频域 DAS (Frequency Domain DAS) 实现
 *
 * CSM (Cross Spectral Matrix)：计算不同麦克风信号之间的互谱矩阵
 * Steering Vector (导向矢量)：在频域计算每个频率的相位延迟（而不是时域的整数移动），精度更高
 * Beamforming Power：利用公式 B = w^H * C * w 计算能量
 * SPL (声压级)：将计算出的能量转换为分贝 (dB)
 *
 * 旧版：2500 * 64 * 46 = 730万个对象
 * 新版：2500 * 64 (临时变量) * 8 (次循环)
 */

object DasCalculator {
    private const val SPEED_OF_SOUND = 340.0
    private const val FREQ_MIN = 2000.0
    private const val FREQ_MAX = 5000.0

    // 关键优化：只分析能量最强的 8 个频率
    // 这能显著减少计算量和内存占用，且几乎不影响定位精度
    private const val TOP_N_FREQS = 8

    /**
     * 计算单个 Steering Vector 元素
     * 用于实时计算，避免存储巨大的 3D 数组
     */
    private fun computeSteeringValue(
        freq: Double,
        mic: Point3D,
        gridPoint: Point3D
    ): Complex {
        val omega = 2 * PI * freq
        val center = Point3D(0f, 0f, 0f) // 假设阵列中心

        // 距离计算
        val rCenter = MicArrayConfig.distance(gridPoint, center).toDouble()
        val rMic = MicArrayConfig.distance(gridPoint, mic).toDouble()

        // 相位延迟
        val distDiff = rMic - rCenter
        val phase = -omega * distDiff / SPEED_OF_SOUND

        // 幅度补偿 (rMic / rCenter)
        val amplitude = rMic / rCenter

        // exp(j * phase) * amplitude
        return expIj(phase) * amplitude
    }

    /**
     * 主入口
     */
    fun computeHeatmap(audioData: AudioData): FloatArray {
        val nMics = audioData.channels
        val nSamples = audioData.data[0].size
        val sampleRate = audioData.sampleRate
        val gridSize = GridConfig.GRID_SIZE
        val nGrid = gridSize * gridSize

        // --- 1. FFT 计算 ---
        // [Mic][FreqIndex] -> Complex
        val fftData = Array(nMics) { ComplexArray(nSamples / 2) }
        val fftLib = FloatFFT_1D(nSamples.toLong())

        // 临时 buffer
        val buffer = FloatArray(nSamples * 2)

        for (m in 0 until nMics) {
            // 填入数据
            for (i in 0 until nSamples) {
                buffer[i * 2] = audioData.data[m][i]
                buffer[i * 2 + 1] = 0f
            }
            fftLib.complexForward(buffer)

            // 只取前一半 (Nyquist)
            for (i in 0 until nSamples / 2) {
                fftData[m][i] = Complex(buffer[i * 2].toDouble(), buffer[i * 2 + 1].toDouble())
            }
        }

        // --- 2. 频率筛选 (Top-N Selection) ---
        // 找出在 FREQ_MIN ~ FREQ_MAX 范围内，所有麦克风总能量最大的那些频率索引
        val validIndices = ArrayList<Int>()
        for (i in 0 until nSamples / 2) {
            val f = i * sampleRate.toDouble() / nSamples
            if (f >= FREQ_MIN && f <= FREQ_MAX) {
                validIndices.add(i)
            }
        }

        if (validIndices.isEmpty()) return FloatArray(nGrid)

        // 排序：按该频率下所有麦克风的能量之和降序排列
        val topIndices = validIndices.sortedByDescending { idx ->
            var sumEnergy = 0.0
            for (m in 0 until nMics) {
                sumEnergy += fftData[m][idx].absSq()
            }
            sumEnergy
        }.take(TOP_N_FREQS) // 只取前 8 个

        val nSelFreqs = topIndices.size

        // --- 3. 计算 CSM (仅针对 Top-N 频率) ---
        // 这样 csm 数组很小: [Mic][Mic][8]
        val csm = Array(nMics) { Array(nMics) { Array(nSelFreqs) { Complex(0.0, 0.0) } } }
        val selectedFreqs = DoubleArray(nSelFreqs)

        for ((k, fIdx) in topIndices.withIndex()) {
            selectedFreqs[k] = fIdx * sampleRate.toDouble() / nSamples
            for (m1 in 0 until nMics) {
                val val1 = fftData[m1][fIdx]
                for (m2 in 0 until nMics) {
                    val val2 = fftData[m2][fIdx]
                    // x * x^H
                    csm[m1][m2][k] = val1 * val2.conj()
                }
            }
        }

        // --- 4. Beamforming (内存安全版) ---
        // 我们不预先计算巨大的 W 数组，而是在循环中实时计算 Steering Vector
        // 虽然稍微增加了一点 CPU 负担，但彻底解决了 OOM 问题

        val powerMap = DoubleArray(nGrid) { 0.0 }
        val mics = MicArrayConfig.mics

        // 预先生成扫描点坐标
        val gridPoints = Array(nGrid) { i ->
            val gy = i / gridSize
            val gx = i % gridSize
            GridConfig.getPoint3D(gx, gy)
        }

        // 对每个网格点
        for (g in 0 until nGrid) {
            val gp = gridPoints[g]

            // 对每个选中的频率
            for (k in 0 until nSelFreqs) {
                val freq = selectedFreqs[k]

                // --- 核心优化：在内层循环计算 B = w^H * CSM * w ---
                // 展开公式: Sum_m1 Sum_m2 ( w[m1]^* * CSM[m1][m2] * w[m2] )
                // 我们可以先算 tempVector = CSM * w
                // 然后算 w^H * tempVector
                // 这样复杂度从 O(M^2) 降到 O(M^2) 但省去了存储 W 的空间

                // 1. 临时计算该点的 Steering Vector (长度为 nMics)
                val wVec = Array(nMics) { m ->
                    computeSteeringValue(freq, mics[m], gp)
                }

                // 2. 矩阵运算: w^H * CSM * w
                var sum = Complex(0.0, 0.0)

                for (m1 in 0 until nMics) {
                    for (m2 in 0 until nMics) {
                        // w[m1].conj * CSM[m1][m2] * w[m2]
                        val term = wVec[m1].conj() * csm[m1][m2][k] * wVec[m2]
                        sum += term
                    }
                }

                powerMap[g] += sum.re
            }
        }

        // --- 5. 转 dB ---
        val splMap = FloatArray(nGrid)
        val epsilon = 1e-12

        // 简单归一化 dB
        for (i in 0 until nGrid) {
            // 防止负数
            val p = max(powerMap[i], epsilon)
            // 20 * log10(sqrt(p)) = 10 * log10(p)
            splMap[i] = (10 * log10(p)).toFloat()
        }

        return splMap
    }

    // 辅助类保持不变
    class ComplexArray(size: Int) {
        val data = Array(size) { Complex(0.0, 0.0) }
        operator fun set(i: Int, value: Complex) { data[i] = value }
        operator fun get(i: Int) = data[i]
    }
}