package com.example.acousticcamera.algorithm

import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.sqrt

object FftUtils {

    /**
     * 输入：时域信号 (比如麦克风录到的波形)
     * 输出：频域幅值 (用于画频谱图)
     */
    fun computeMagnitudeSpectrum(timeData: FloatArray): FloatArray {
        val n = timeData.size
        // JTransforms 需要的数据格式：偶数位是实部，奇数位是虚部
        // 所以数组长度要是原来的2倍
        val fftData = FloatArray(n * 2)

        // 1. 填充实部，虚部默认为0
        for (i in 0 until n) {
            fftData[i * 2] = timeData[i]
            fftData[i * 2 + 1] = 0f
        }

        // 2. 执行 FFT 计算
        val fft = FloatFFT_1D(n.toLong())
        fft.complexForward(fftData)

        // 3. 计算幅值 (Magnitude) = sqrt(实部^2 + 虚部^2)
        // 只需要取前一半的数据 (Nyquist频率)
        val magnitude = FloatArray(n / 2)
        for (i in 0 until n / 2) {
            val re = fftData[i * 2]
            val im = fftData[i * 2 + 1]
            magnitude[i] = sqrt(re * re + im * im)
        }

        return magnitude
    }
}