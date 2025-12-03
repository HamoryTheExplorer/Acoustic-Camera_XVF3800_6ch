package com.example.acousticcamera.algorithm

import com.example.acousticcamera.data.AudioData
import com.example.acousticcamera.data.GridConfig
import com.example.acousticcamera.data.MicArrayConfig
import com.example.acousticcamera.data.Point3D
import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

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
    // 只分析能量最强的 8 个频率
    // 显著减少计算量和内存占用，且几乎不影响定位精度
    private const val TOP_N_FREQS = 8

    /**
     * 计算相位偏移因子 (Unit Steering Vector element)
     * 优化：去掉了 amplitude (rMic/rCenter) 的除法计算，只保留相位，计算更快且对热力图影响极小
     */
    private fun computePhaseFactor(
        omega: Double,
        mic: Point3D,
        gridPoint: Point3D
    ): Complex {
        // 假设阵列中心
        val center = Point3D(0f, 0f, 0f)

        // 距离计算
        val rCenter = MicArrayConfig.distance(gridPoint, center).toDouble()
        val rMic = MicArrayConfig.distance(gridPoint, mic).toDouble()

        // 核心：计算相位差
        // 这里的符号取决于你的 FFT 实现和延迟定义，通常是 exp(-j * w * delta_t)
        val distDiff = rMic - rCenter
        val phase = -omega * distDiff / SPEED_OF_SOUND

        // 欧拉公式 exp(jx) = cos(x) + j*sin(x)
        return Complex(cos(phase), sin(phase))
    }

    /**
     * 主入口
     * 这里加上了 suspend，因为我们要用协程并行计算
     */
    suspend fun computeHeatmap(audioData: AudioData): FloatArray = withContext(Dispatchers.Default) {
        val nMics = audioData.channels
        val nSamples = audioData.data[0].size
        val sampleRate = audioData.sampleRate
        val gridSize = GridConfig.GRID_SIZE
        val nGrid = gridSize * gridSize

        // --- 1. FFT 计算 (保持不变) ---
        val fftData = Array(nMics) { ComplexArray(nSamples / 2) }
        val fftLib = FloatFFT_1D(nSamples.toLong())
        val buffer = FloatArray(nSamples * 2)

        for (m in 0 until nMics) {
            for (i in 0 until nSamples) {
                buffer[i * 2] = audioData.data[m][i]
                buffer[i * 2 + 1] = 0f
            }
            fftLib.complexForward(buffer)
            for (i in 0 until nSamples / 2) {
                fftData[m][i] = Complex(buffer[i * 2].toDouble(), buffer[i * 2 + 1].toDouble())
            }
        }

        // --- 2. 频率筛选 (Top-N) (保持不变) ---
        val validIndices = ArrayList<Int>()
        for (i in 0 until nSamples / 2) {
            val f = i * sampleRate.toDouble() / nSamples
            if (f in FREQ_MIN..FREQ_MAX) {
                validIndices.add(i)
            }
        }
        if (validIndices.isEmpty()) return@withContext FloatArray(nGrid)

        val topIndices = validIndices.sortedByDescending { idx ->
            var sumEnergy = 0.0
            for (m in 0 until nMics) sumEnergy += fftData[m][idx].absSq()
            sumEnergy
        }.take(TOP_N_FREQS)

        val nSelFreqs = topIndices.size

        // --- 3. 提取选中频率的信号向量 (优化点：不做 CSM 矩阵，只保留向量) ---
        // signalVectors[FreqIndex][MicIndex]
        val signalVectors = Array(nSelFreqs) { k ->
            val fIdx = topIndices[k]
            Array(nMics) { m -> fftData[m][fIdx] }
        }

        // 预计算角频率
        val omegas = DoubleArray(nSelFreqs) { k ->
            2 * PI * (topIndices[k] * sampleRate.toDouble() / nSamples)
        }

        // --- 4. 并行波束形成 (Parallel Beamforming) ---
        // 最大的优化点：将 2500 个点切分成多块，利用 CPU 多核并行计算
        val mics = MicArrayConfig.mics

        // 我们不一次循环 2500 次，而是使用 map + async 并行
        // 将 Grid 坐标预先生成
        val allGridIndices = (0 until nGrid).toList()

        // 分块处理，根据 CPU 核心数决定块的数量
        val numChunks = Runtime.getRuntime().availableProcessors() * 2
        val chunkSize = (nGrid + numChunks - 1) / numChunks

        val deferredResults = allGridIndices.chunked(chunkSize).map { chunkIndices ->
            async {
                val chunkResults = DoubleArray(chunkIndices.size)

                // 在这个线程块里处理一部分 Grid Point
                for ((localIndex, gIdx) in chunkIndices.withIndex()) {
                    val gy = gIdx / gridSize
                    val gx = gIdx % gridSize
                    val gp = GridConfig.getPoint3D(gx, gy)

                    var totalEnergy = 0.0

                    // 对每个频率累加能量
                    for (k in 0 until nSelFreqs) {
                        val omega = omegas[k]
                        val signals = signalVectors[k] // 当前频率下的麦克风信号向量

                        // 核心数学优化：
                        // 原算法：w^H * CSM * w (复杂度 64^2 = 4096)
                        // 新算法：|w^H * x|^2 (复杂度 64)
                        // 解释：如果是一次快拍(Snapshot)，两者数学上完全等价，但后者快 64 倍

                        var beamSum = Complex(0.0, 0.0)

                        for (m in 0 until nMics) {
                            // 计算相位补偿 (Steering Vector element)
                            // 注意：这里用 conjugate (共轭) 还是原值，取决于定义
                            // DAS原理：补偿相位延迟 -> 乘以 exp(+jw*delta) 抵消 exp(-jw*delta)
                            // 这里 computePhaseFactor 计算的是 exp(phase)，我们需要它的共轭来抵消信号里的延迟
                            // 或者简单理解：信号延后了，我们要把相角"减"回来

                            val w = computePhaseFactor(omega, mics[m], gp)

                            // Beamforming Sum = Sum( w[m].conj * signal[m] )
                            // 相当于把每个麦克风的信号"对齐"后相加
                            beamSum += w.conj() * signals[m]
                        }

                        // 能量 = 幅度的平方
                        totalEnergy += beamSum.absSq()
                    }
                    chunkResults[localIndex] = totalEnergy
                }
                chunkResults
            }
        }

        // 等待所有线程完成并合并结果
        val powerMap = DoubleArray(nGrid)
        var offset = 0
        deferredResults.awaitAll().forEach { chunkRes ->
            System.arraycopy(chunkRes, 0, powerMap, offset, chunkRes.size)
            offset += chunkRes.size
        }

        // --- 5. 转 dB (保持不变) ---
        val splMap = FloatArray(nGrid)
        val epsilon = 1e-12
        for (i in 0 until nGrid) {
            val p = max(powerMap[i], epsilon)
            splMap[i] = (10 * log10(p)).toFloat()
        }

        return@withContext splMap
    }

    // 辅助类
    class ComplexArray(size: Int) {
        val data = Array(size) { Complex(0.0, 0.0) }
        operator fun set(i: Int, value: Complex) { data[i] = value }
        operator fun get(i: Int) = data[i]
    }
}