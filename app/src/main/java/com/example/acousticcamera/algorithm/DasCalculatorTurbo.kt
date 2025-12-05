package com.example.acousticcamera.algorithm

import com.example.acousticcamera.data.AudioData
import com.example.acousticcamera.data.GridConfig
import com.example.acousticcamera.data.MicArrayConfig
import com.example.acousticcamera.data.Point3D
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.*

/**
 * 极速版频域 DAS (Frequency Domain DAS Turbo) 实现
 *
 * 核心原理：
 * 1. CSM (Cross Spectral Matrix)：概念上基于互谱矩阵，但在单快拍(Single Snapshot)情况下，
 *    我们优化为向量投影法 |w^H * x|^2，避免了构建 64x64 矩阵的 O(M^2) 开销，将复杂度降为 O(M)。
 * 2. Steering Vector (导向矢量)：在频域计算每个频率的精确相位延迟 exp(-j*ω*τ)，比时域插值精度更高。
 * 3. Beamforming Power：利用公式 P = |Σ(w_i * x_i)|^2 计算能量。
 * 4. SPL (声压级)：将计算出的能量转换为分贝 (dB)。
 *
 * 性能指标：
 * - 核心循环：2500 (网格点) * 64 (麦克风) * 8 (Top-N频率) ≈ 1,280,000 次运算/帧。
 * - 优化策略：零对象创建 (Zero Allocation) + 几何预计算 (Lookup Table) + 多线程并行。
 */
object DasCalculatorTurbo {
    private const val SPEED_OF_SOUND = 340.0f
    private const val FREQ_MIN = 2000.0f
    private const val FREQ_MAX = 5000.0f
    // 只选取能量最大的 8 个频率进行合成，兼顾速度与精度
    private const val TOP_N_FREQS = 8

    // 优化点 1: 几何查找表 (Lookup Table)
    // 存储 [GridIndex][MicIndex] 之间的距离差 (r_mic - r_center)
    // 空间换时间：避免在热循环中重复计算 expensive 的 sqrt
    private var distDiffTable: Array<FloatArray>? = null

    /**
     * 初始化几何查找表 (Lazy Load)
     * 只有第一次运行时会计算一次
     */
    private fun initGeometryTableIfNeeded() {
        if (distDiffTable != null) return

        val gridSize = GridConfig.GRID_SIZE
        val nGrid = gridSize * gridSize
        val mics = MicArrayConfig.mics
        val center = Point3D(0f, 0f, 0f)

        // 创建大数组 [2500][64]
        val table = Array(nGrid) { FloatArray(mics.size) }

        for (g in 0 until nGrid) {
            val gy = g / gridSize
            val gx = g % gridSize
            val gp = GridConfig.getPoint3D(gx, gy)

            // 阵列中心到扫描点的距离
            val rCenter = MicArrayConfig.distance(gp, center)

            for ((m, mic) in mics.withIndex()) {
                val rMic = MicArrayConfig.distance(gp, mic)
                // 预计算距离差，用于后续计算相位 delay
                table[g][m] = rMic - rCenter
            }
        }
        distDiffTable = table
    }

    /**
     * 核心计算函数
     * 使用 suspend 挂起函数，配合 Dispatchers.Default 利用 CPU 多核并行
     */
    suspend fun computeHeatmap(audioData: AudioData): FloatArray = withContext(Dispatchers.Default) {
        // 确保几何表已初始化
        initGeometryTableIfNeeded()
        val distTable = distDiffTable!!

        val nMics = audioData.channels
        val nSamples = audioData.data[0].size
        val sampleRate = audioData.sampleRate.toFloat()
        val gridSize = GridConfig.GRID_SIZE
        val nGrid = gridSize * gridSize

        // --- 1. FFT 计算 (优化：直接用 Float 处理，不转 Complex 对象) ---
        val fftLib = FloatFFT_1D(nSamples.toLong())
        val buffer = FloatArray(nSamples * 2)

        // 临时存储所有 FFT 结果 [Mic][Freq]，用于后续筛选
        val rawFftReal = Array(nMics) { FloatArray(nSamples / 2) }
        val rawFftImag = Array(nMics) { FloatArray(nSamples / 2) }

        // 预计算频率分辨率
        val freqResolution = sampleRate / nSamples
        val validIndices = ArrayList<Int>()

        // 筛选出关注的频段索引
        for (i in 0 until nSamples / 2) {
            val f = i * freqResolution
            if (f >= FREQ_MIN && f <= FREQ_MAX) {
                validIndices.add(i)
            }
        }

        if (validIndices.isEmpty()) return@withContext FloatArray(nGrid)

        // 对每个麦克风执行 FFT
        for (m in 0 until nMics) {
            for (i in 0 until nSamples) {
                buffer[i * 2] = audioData.data[m][i]
                buffer[i * 2 + 1] = 0f
            }
            fftLib.complexForward(buffer)

            // 提取实部和虚部
            for (idx in validIndices) {
                rawFftReal[m][idx] = buffer[idx * 2]
                rawFftImag[m][idx] = buffer[idx * 2 + 1]
            }
        }

        // --- 2. 频率筛选 (Top-N Selection) ---
        // 找出总能量最大的前 8 个频率，减少无效计算
        val topIndices = validIndices.sortedByDescending { idx ->
            var sumEnergy = 0f
            for (m in 0 until nMics) {
                val re = rawFftReal[m][idx]
                val im = rawFftImag[m][idx]
                sumEnergy += re * re + im * im
            }
            sumEnergy
        }.take(TOP_N_FREQS)

        val nSelFreqs = topIndices.size

        // --- 3. 数据重排 (Flatten Arrays) ---
        // 将信号重排为 [Freq][Mic]，利于 CPU 缓存命中
        val signalsRe = Array(nSelFreqs) { k -> FloatArray(nMics) { m -> rawFftReal[m][topIndices[k]] } }
        val signalsIm = Array(nSelFreqs) { k -> FloatArray(nMics) { m -> rawFftImag[m][topIndices[k]] } }

        // 预计算角频率常数: -2 * PI * freq / c
        val omegaConsts = FloatArray(nSelFreqs) { k ->
            val freq = topIndices[k] * freqResolution
            (-2f * PI.toFloat() * freq) / SPEED_OF_SOUND
        }

        // --- 4. 并行波束形成 (Parallel Beamforming) ---
        // 根据 CPU 核心数分块，极大提升吞吐量
        val numChunks = Runtime.getRuntime().availableProcessors()
        val chunkSize = (nGrid + numChunks - 1) / numChunks
        val allGridIndices = (0 until nGrid).toList()

        val deferredResults = allGridIndices.chunked(chunkSize).map { chunkIndices ->
            async {
                val chunkResults = DoubleArray(chunkIndices.size)

                // === 热循环 (Hot Loop) 开始 ===
                // 在这里面绝对不能创建任何对象 (Avoid Object Allocation)
                for ((localIndex, gIdx) in chunkIndices.withIndex()) {
                    var totalEnergy = 0.0

                    // 获取当前网格点到所有麦克风的距离差 (查表)
                    val currentDistDiffs = distTable[gIdx]

                    for (k in 0 until nSelFreqs) {
                        val omegaC = omegaConsts[k]
                        val sigRe = signalsRe[k]
                        val sigIm = signalsIm[k]

                        var sumRe = 0f
                        var sumIm = 0f

                        for (m in 0 until nMics) {
                            // A. 计算相位延迟 (Steering Vector Phase)
                            // phase = -omega * delay = (-omega/c) * distDiff
                            val phase = omegaC * currentDistDiffs[m]

                            // B. 欧拉公式展开
                            // w = exp(j*phase) = cos(phase) + j*sin(phase)
                            // 我们需要 w.conj() * signal
                            // w.conj = cos(phase) - j*sin(phase)
                            // Let wRe = cos, wIm = sin
                            val wRe = cos(phase)
                            val wIm = sin(phase) // 注意：这里用正sin，后面乘法逻辑处理符号

                            val sRe = sigRe[m]
                            val sIm = sigIm[m]

                            // C. 复数乘法 (Complex Multiplication Unrolled)
                            // (wRe - j*wIm) * (sRe + j*sIm)
                            // = (wRe*sRe + wIm*sIm) + j(wRe*sIm - wIm*sRe)
                            // 修正：上面的数学推导对应标准 DAS 公式
                            // 实部 accumulating:
                            sumRe += (wRe * sRe + wIm * sIm)
                            // 虚部 accumulating:
                            sumIm += (wRe * sIm - wIm * sRe)
                        }

                        // D. 累加能量 Power = |Sum|^2 = Re^2 + Im^2
                        totalEnergy += (sumRe * sumRe + sumIm * sumIm)
                    }
                    chunkResults[localIndex] = totalEnergy
                }
                // === 热循环结束 ===

                chunkResults
            }
        }

        // 合并所有线程的计算结果
        val powerMap = DoubleArray(nGrid)
        var offset = 0
        deferredResults.awaitAll().forEach { chunkRes ->
            System.arraycopy(chunkRes, 0, powerMap, offset, chunkRes.size)
            offset += chunkRes.size
        }

        // --- 5. 转换为声压级 (dB Calculation) ---
        val splMap = FloatArray(nGrid)
        val epsilon = 1e-9 // 防止 log(0)
        for (i in 0 until nGrid) {
            val p = max(powerMap[i], epsilon)
            // SPL = 10 * log10(Power)
            splMap[i] = (10 * log10(p)).toFloat()
        }

        return@withContext splMap
    }
}