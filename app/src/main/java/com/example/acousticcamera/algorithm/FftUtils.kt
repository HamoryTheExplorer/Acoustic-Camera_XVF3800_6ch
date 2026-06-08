package com.example.acousticcamera.algorithm

import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.sqrt

object FftUtils {

    // 缓存 FFT 对象与输入缓冲区，避免每帧重复分配（调用方保证单线程顺序调用）
    private var fftLib: FloatFFT_1D? = null
    private var fftBuffer: FloatArray? = null
    private var lastN = -1

    /**
     * 输入：时域信号 (比如麦克风录到的波形)
     * 输出：频域幅值 (用于画频谱图)
     */
    fun computeMagnitudeSpectrum(timeData: FloatArray): FloatArray {
        val n = timeData.size

        // 首次调用或 n 变化时才重新分配（正常情况下 n 固定为 4096）
        if (n != lastN) {
            fftLib = FloatFFT_1D(n.toLong())
            fftBuffer = FloatArray(n * 2)
            lastN = n
        }
        val buf = fftBuffer!!

        // 1. 填充实部，虚部为 0（JTransforms 要求偶数位实部、奇数位虚部）
        for (i in 0 until n) {
            buf[i * 2]     = timeData[i]
            buf[i * 2 + 1] = 0f
        }

        // 2. 执行 FFT 计算
        fftLib!!.complexForward(buf)

        // 3. 计算幅值 (Magnitude) = sqrt(实部^2 + 虚部^2)
        // magnitude 每帧必须新建：返回给 StateFlow 后 UI 线程会读它，复用会有数据竞争
        val magnitude = FloatArray(n / 2)
        for (i in 0 until n / 2) {
            val re = buf[i * 2]
            val im = buf[i * 2 + 1]
            magnitude[i] = sqrt(re * re + im * im)
        }

        return magnitude
    }
}
