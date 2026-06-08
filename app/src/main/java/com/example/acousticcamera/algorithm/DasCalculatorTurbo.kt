package com.example.acousticcamera.algorithm

import com.example.acousticcamera.data.AudioData
import com.example.acousticcamera.data.GridConfig
import com.example.acousticcamera.data.MicArrayConfig
import com.example.acousticcamera.data.Point3D
import android.os.Process
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.*

/**
 * 创建一个高线程优先级的 Dispatcher
 * 安卓系统性能优化
 */
val HighPriorityDispatcher = Executors.newFixedThreadPool(
    Runtime.getRuntime().availableProcessors(), // 线程数 = CPU核心数
    object : ThreadFactory {
        override fun newThread(r: Runnable): Thread {
            val t = Thread {
                // 关键点：设置线程优先级为 "URGENT_AUDIO" (-19) 或 "DISPLAY" (-4)
                // 这会告诉 Linux 调度器把这个线程放到大核上
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                r.run()
            }
            t.name = "Das-Compute-Thread"
            return t
        }
    }
).asCoroutineDispatcher()

/**
 * computeHeatmap 的返回值：热力图 + 信道 0 的幅值频谱（复用 DAS 内部 FFT，避免二次计算）。
 * 两个数组均为每帧新建，调用方可安全地将其存入 StateFlow 供 UI 线程读取。
 */
data class BeamformResult(val heatmap: FloatArray, val spectrum: FloatArray)

/**
 * 频域 DAS (Frequency Domain DAS Turbo) 实现
 *
 * 核心原理：
 * 1. CSM (Cross Spectral Matrix)：跨谱矩阵波束成形，多快拍 EMA 平均降噪。
 * 2. Steering Vector (导向矢量)：预计算查找表 (LUT)，热循环中查表替代 cos/sin。
 * 3. Beamforming Power：P = h^H * C * h，h 为导向矢量，C 为平均 CSM。
 * 4. SPL (声压级)：将计算出的能量转换为分贝 (dB)。
 *
 * 优化：
 * - CSM EMA 平均 (α=0.20) — 多快拍稳定热力图
 * - 对角线移除 — 抑制麦克风自噪声
 * - 导向矢量 LUT — 热循环无 cos/sin
 * - 零对象创建 (Zero Allocation) + 几何预计算 + 多线程并行
 */
object DasCalculatorTurbo {
    private const val SPEED_OF_SOUND = 340.0f
    private const val FREQ_MIN = 2000.0f
    private const val FREQ_MAX = 5000.0f
    private const val TOP_N_FREQS = 8

    // CSM EMA 指数平均因子（0<α≤1，越小越平滑，响应越慢）
    private const val CSM_EMA_ALPHA = 0.20f
    private const val CSM_EMA_ALPHA_COMPL = 1.0f - CSM_EMA_ALPHA  // 0.80f

    // --- 复用缓冲区 (initIfNeeded 分配，reset 置 null) ---

    // 几何查找表 [nGrid][nMics]
    private var distDiffTable: Array<FloatArray>? = null

    // FFT
    private var fftLib: FloatFFT_1D? = null
    private var fftBuffer: FloatArray? = null           // [nSamples * 2]
    private var rawFftReal: Array<FloatArray>? = null   // [nMics][nFreqBins]
    private var rawFftImag: Array<FloatArray>? = null
    private var validFreqIndices: IntArray? = null      // 关注频段内的 FFT bin 索引
    private var freqResolution: Float = 0f
    private var hannWindow: FloatArray? = null

    // CSM 平均缓冲区 [nValidBins][nMics][nMics] — Hermitian，完整存储便于热循环
    private var csmAvgRe: Array<Array<FloatArray>>? = null
    private var csmAvgIm: Array<Array<FloatArray>>? = null
    // FFT bin → validIdx 查表 (-1 = 不在关注频段) [nSamples/2]
    private var fftBinToValidIdx: IntArray? = null

    // 导向矢量 LUT [nGrid][TOP_N_FREQS][nMics] — 每帧根据 Top-N 频率重新填充
    private var steeringCos: Array<Array<FloatArray>>? = null
    private var steeringSin: Array<Array<FloatArray>>? = null

    // Top-N 工作数组
    private var topFreqIdxBuf: IntArray? = null
    private var topEnergiesBuf: FloatArray? = null
    private var omegaConstsBuf: FloatArray? = null

    // 正交波束成形：每频点 CSM 主特征向量 (power iteration, 4×4→O(4) hot loop)
    private var eigenValBuf: FloatArray? = null          // [TOP_N_FREQS]
    private var eigenVecReBuf: Array<FloatArray>? = null // [TOP_N_FREQS][nMics]
    private var eigenVecImBuf: Array<FloatArray>? = null

    // 并行分块
    private var chunkedGridIndices: Array<IntArray>? = null
    private var chunkResultBuffers: Array<DoubleArray>? = null
    private var powerMapBuf: DoubleArray? = null

    fun reset() {
        fftLib             = null
        fftBuffer          = null
        rawFftReal         = null
        rawFftImag         = null
        validFreqIndices   = null
        freqResolution     = 0f
        hannWindow         = null
        csmAvgRe           = null
        csmAvgIm           = null
        fftBinToValidIdx   = null
        steeringCos        = null
        steeringSin        = null
        topFreqIdxBuf      = null
        topEnergiesBuf     = null
        omegaConstsBuf     = null
        eigenValBuf        = null
        eigenVecReBuf      = null
        eigenVecImBuf      = null
        chunkedGridIndices = null
        chunkResultBuffers = null
        powerMapBuf        = null
        distDiffTable      = null
    }

    private fun initIfNeeded(audioData: AudioData) {
        if (fftLib != null) return

        val nSamples = audioData.data[0].size
        val nMics = audioData.channels
        val sampleRate = audioData.sampleRate.toFloat()
        val gridSize = GridConfig.GRID_SIZE
        val nGrid = gridSize * gridSize
        val numCores = Runtime.getRuntime().availableProcessors()

        // FFT
        fftLib = FloatFFT_1D(nSamples.toLong())
        fftBuffer = FloatArray(nSamples * 2)

        // 每路麦克风 FFT 输出
        rawFftReal = Array(nMics) { FloatArray(nSamples / 2) }
        rawFftImag = Array(nMics) { FloatArray(nSamples / 2) }

        // 有效频段索引
        freqResolution = sampleRate / nSamples
        val tempValid = ArrayList<Int>()
        for (i in 0 until nSamples / 2) {
            val f = i * freqResolution
            if (f >= FREQ_MIN && f <= FREQ_MAX) tempValid.add(i)
        }
        validFreqIndices = tempValid.toIntArray()

        val nValidBins = validFreqIndices!!.size

        // FFT bin → validIdx 查表
        fftBinToValidIdx = IntArray(nSamples / 2) { -1 }
        for (vi in 0 until nValidBins) {
            fftBinToValidIdx!![validFreqIndices!![vi]] = vi
        }

        // CSM 平均缓冲区 [nValidBins][nMics][nMics]
        csmAvgRe = Array(nValidBins) { Array(nMics) { FloatArray(nMics) } }
        csmAvgIm = Array(nValidBins) { Array(nMics) { FloatArray(nMics) } }

        // 导向矢量 LUT [nGrid][TOP_N_FREQS][nMics]
        steeringCos = Array(nGrid) { Array(TOP_N_FREQS) { FloatArray(nMics) } }
        steeringSin = Array(nGrid) { Array(TOP_N_FREQS) { FloatArray(nMics) } }

        // 并行分块（网格固定，结果每帧相同）
        val chunkSize = (nGrid + numCores - 1) / numCores
        val chunks = ArrayList<IntArray>(numCores)
        var start = 0
        while (start < nGrid) {
            val end = minOf(start + chunkSize, nGrid)
            chunks.add(IntArray(end - start) { it + start })
            start = end
        }
        chunkedGridIndices = chunks.toTypedArray()
        chunkResultBuffers = Array(chunks.size) { i -> DoubleArray(chunks[i].size) }
        powerMapBuf = DoubleArray(nGrid)

        // Top-N 工作数组
        topFreqIdxBuf = IntArray(TOP_N_FREQS)
        topEnergiesBuf = FloatArray(TOP_N_FREQS)
        omegaConstsBuf = FloatArray(TOP_N_FREQS)

        // 正交波束成形缓冲区
        eigenValBuf   = FloatArray(TOP_N_FREQS)
        eigenVecReBuf = Array(TOP_N_FREQS) { FloatArray(nMics) }
        eigenVecImBuf = Array(TOP_N_FREQS) { FloatArray(nMics) }

        // Hann 窗
        hannWindow = FloatArray(nSamples)
        val win = hannWindow!!
        val winNminus1 = (nSamples - 1).toFloat()
        for (i in 0 until nSamples) {
            win[i] = (0.5f * (1.0f - kotlin.math.cos((2.0 * PI * i) / winNminus1))).toFloat()
        }

        // 几何查找表
        initGeometryTable(nGrid, gridSize)
    }

    private fun initGeometryTable(nGrid: Int, gridSize: Int) {
        val mics = MicArrayConfig.mics
        val center = Point3D(0f, 0f, 0f)
        val table = Array(nGrid) { FloatArray(mics.size) }
        for (g in 0 until nGrid) {
            val gp = GridConfig.getPoint3D(g % gridSize, g / gridSize)
            val rCenter = MicArrayConfig.distance(gp, center)
            for (m in mics.indices) {
                table[g][m] = MicArrayConfig.distance(gp, mics[m]) - rCenter
            }
        }
        distDiffTable = table
    }

    private fun selectTopNFreqs(
        validIndices: IntArray,
        rawFftReal: Array<FloatArray>,
        rawFftImag: Array<FloatArray>,
        nMics: Int,
        topOut: IntArray,
        energiesOut: FloatArray
    ): Int {
        val n = minOf(TOP_N_FREQS, validIndices.size)
        energiesOut.fill(-1f, 0, n)

        for (idx in validIndices) {
            var energy = 0f
            for (m in 0 until nMics) {
                val re = rawFftReal[m][idx]
                val im = rawFftImag[m][idx]
                energy += re * re + im * im
            }
            var minPos = 0
            for (i in 1 until n) {
                if (energiesOut[i] < energiesOut[minPos]) minPos = i
            }
            if (energy > energiesOut[minPos]) {
                energiesOut[minPos] = energy
                topOut[minPos] = idx
            }
        }
        return n
    }

    suspend fun computeHeatmap(audioData: AudioData): BeamformResult = withContext(HighPriorityDispatcher) {
        initIfNeeded(audioData)

        val distTable      = distDiffTable!!
        val fftLib         = fftLib!!
        val fftBuffer      = fftBuffer!!
        val rawFftReal     = rawFftReal!!
        val rawFftImag     = rawFftImag!!
        val validFreqs     = validFreqIndices!!
        val fft2valid      = fftBinToValidIdx!!
        val chunkedGrid    = chunkedGridIndices!!
        val chunkBufs      = chunkResultBuffers!!
        val powerMap       = powerMapBuf!!
        val topFreqIdx     = topFreqIdxBuf!!
        val topEnergies    = topEnergiesBuf!!
        val omegaConsts    = omegaConstsBuf!!
        val hannWin        = hannWindow!!
        val csmRe          = csmAvgRe!!
        val csmIm          = csmAvgIm!!
        val sCos           = steeringCos!!
        val sSin           = steeringSin!!
        val eigenVal       = eigenValBuf!!
        val eigenVecRe     = eigenVecReBuf!!
        val eigenVecIm     = eigenVecImBuf!!

        val nMics    = audioData.channels
        val nSamples = audioData.data[0].size
        val nGrid    = GridConfig.GRID_SIZE * GridConfig.GRID_SIZE

        if (validFreqs.isEmpty() || nMics < 2)
            return@withContext BeamformResult(FloatArray(nGrid), FloatArray(nSamples / 2))

        // --- 1. FFT (4 路独立) ---
        var spectrumResult: FloatArray? = null
        for (m in 0 until nMics) {
            val micData = audioData.data[m]
            for (i in 0 until nSamples) {
                fftBuffer[i * 2]     = micData[i] * hannWin[i]
                fftBuffer[i * 2 + 1] = 0f
            }
            fftLib.complexForward(fftBuffer)
            if (m == 0) {
                val halfN = nSamples / 2
                val spec  = FloatArray(halfN)
                for (i in 0 until halfN) {
                    val re = fftBuffer[i * 2]
                    val im = fftBuffer[i * 2 + 1]
                    spec[i] = sqrt(re * re + im * im)
                }
                spectrumResult = spec
            }
            val realRow = rawFftReal[m]
            val imagRow = rawFftImag[m]
            for (idx in validFreqs) {
                realRow[idx] = fftBuffer[idx * 2]
                imagRow[idx] = fftBuffer[idx * 2 + 1]
            }
        }

        // --- 2. CSM EMA 更新 (多快拍平均) ---
        for (vi in validFreqs.indices) {
            val fftBin = validFreqs[vi]
            val cr = csmRe[vi]
            val ci = csmIm[vi]

            for (i in 0 until nMics) {
                val reI = rawFftReal[i][fftBin]
                val imI = rawFftImag[i][fftBin]
                val crI = cr[i]
                val ciI = ci[i]

                for (j in 0 until nMics) {
                    // snapshot CSM: C(i,j) = s[i] * conj(s[j])
                    val snapRe = reI * rawFftReal[j][fftBin] + imI * rawFftImag[j][fftBin]
                    val snapIm = imI * rawFftReal[j][fftBin] - reI * rawFftImag[j][fftBin]

                    // EMA: C_avg = α * C_snap + (1-α) * C_prev
                    crI[j] = CSM_EMA_ALPHA * snapRe + CSM_EMA_ALPHA_COMPL * crI[j]
                    ciI[j] = CSM_EMA_ALPHA * snapIm + CSM_EMA_ALPHA_COMPL * ciI[j]
                }
            }

            // 对角线移除：抑制麦克风自噪声
            for (m in 0 until nMics) {
                cr[m][m] = 0f
                ci[m][m] = 0f
            }
        }

        // --- 3. Top-N 频率选择 (基于原始 FFT 能量，快速响应新声源) ---
        val nSelFreqs = selectTopNFreqs(validFreqs, rawFftReal, rawFftImag, nMics, topFreqIdx, topEnergies)

        // --- 4. 填充 omegaConsts (频率缩放因子) ---
        for (k in 0 until nSelFreqs) {
            omegaConsts[k] = (-2f * PI.toFloat() * topFreqIdx[k] * freqResolution) / SPEED_OF_SOUND
        }

        // --- 5. 正交波束成形：每频点 CSM 主特征值 + 特征向量 (power iteration) ---
        // 仅保留 CSM 的主导特征模态，滤除弱源 cross-talk
        for (k in 0 until nSelFreqs) {
            val csmIdx = fft2valid[topFreqIdx[k]]
            val cr = csmRe[csmIdx]
            val ci = csmIm[csmIdx]

            // power iteration on 4×4 Hermitian CSM (4 iterations enough)
            val evRe = floatArrayOf(1f, 0f, 0f, 0f)
            val evIm = floatArrayOf(0f, 0f, 0f, 0f)
            for (iter in 0 until 4) {
                val wRe = FloatArray(4)
                val wIm = FloatArray(4)
                for (i in 0..3) {
                    val cri = cr[i]; val cii = ci[i]
                    wRe[i] = cri[0]*evRe[0] - cii[0]*evIm[0] + cri[1]*evRe[1] - cii[1]*evIm[1] +
                             cri[2]*evRe[2] - cii[2]*evIm[2] + cri[3]*evRe[3] - cii[3]*evIm[3]
                    wIm[i] = cii[0]*evRe[0] + cri[0]*evIm[0] + cii[1]*evRe[1] + cri[1]*evIm[1] +
                             cii[2]*evRe[2] + cri[2]*evIm[2] + cii[3]*evRe[3] + cri[3]*evIm[3]
                }
                var nrm = 0f
                for (i in 0..3) nrm += wRe[i]*wRe[i] + wIm[i]*wIm[i]
                nrm = 1f / sqrt(nrm)
                for (i in 0..3) { evRe[i] = wRe[i] * nrm; evIm[i] = wIm[i] * nrm }
            }

            // Rayleigh quotient: λ = ev^H * C * ev
            var lambda = 0f
            for (i in 0..3) {
                val cri = cr[i]; val cii = ci[i]
                for (j in 0..3) {
                    // (evRe[i]-j*evIm[i]) * (cr+i*ci)[i][j] * (evRe[j]+j*evIm[j]), real part
                    lambda += evRe[i]*cri[j]*evRe[j] + evIm[i]*cii[j]*evRe[j]
                            - evRe[i]*cii[j]*evIm[j] + evIm[i]*cri[j]*evIm[j]
                }
            }

            eigenVal[k] = lambda
            for (m in 0..3) { eigenVecRe[k][m] = evRe[m]; eigenVecIm[k][m] = evIm[m] }
        }

        // --- 6. 预计算导向矢量 LUT ---
        for (g in 0 until nGrid) {
            val dd = distTable[g]
            for (k in 0 until nSelFreqs) {
                val omegaC = omegaConsts[k]
                val cosRow = sCos[g][k]
                val sinRow = sSin[g][k]
                for (m in 0 until nMics) {
                    val phase = omegaC * dd[m]
                    cosRow[m] = cos(phase)
                    sinRow[m] = sin(phase)
                }
            }
        }

        // --- 7. 并行波束形成 (正交 CSM: P = λ * |h^H * v|²) ---
        val jobs = chunkedGrid.indices.map { chunkIdx ->
            val gridChunk = chunkedGrid[chunkIdx]
            val results   = chunkBufs[chunkIdx]
            async {
                for (localIdx in gridChunk.indices) {
                    val gIdx = gridChunk[localIdx]
                    var totalEnergy = 0.0

                    for (k in 0 until nSelFreqs) {
                        val cosRow = sCos[gIdx][k]
                        val sinRow = sSin[gIdx][k]
                        val evRe = eigenVecRe[k]
                        val evIm = eigenVecIm[k]

                        // h^H * v = Σ_m (cos[m] + j*sin[m]) * (evRe[m] + j*evIm[m])
                        //         = Σ_m (cos*evRe - sin*evIm) + j*(cos*evIm + sin*evRe)
                        var sumRe = 0f; var sumIm = 0f
                        for (m in 0 until nMics) {
                            sumRe += cosRow[m]*evRe[m] - sinRow[m]*evIm[m]
                            sumIm += cosRow[m]*evIm[m] + sinRow[m]*evRe[m]
                        }
                        totalEnergy += (eigenVal[k] * (sumRe*sumRe + sumIm*sumIm)).toDouble()
                    }
                    results[localIdx] = totalEnergy
                }
            }
        }
        jobs.awaitAll()

        // --- 8. 合并分块结果 ---
        var offset = 0
        for (chunkRes in chunkBufs) {
            System.arraycopy(chunkRes, 0, powerMap, offset, chunkRes.size)
            offset += chunkRes.size
        }

        // --- 9. 转换为声压级 (dB) ---
        val splMap = FloatArray(nGrid)
        val epsilon = 1e-9
        for (i in 0 until nGrid) {
            splMap[i] = (10.0 * log10(maxOf(powerMap[i], epsilon))).toFloat()
        }

        BeamformResult(heatmap = splMap, spectrum = spectrumResult!!)
    }
}
