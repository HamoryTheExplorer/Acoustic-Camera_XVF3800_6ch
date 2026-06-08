package com.example.acousticcamera.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * 通道独立性验证（互相关测试）。
 *
 * 从 [AudioSource.audioStream] 收集 10 帧数据，计算 ch0-ch1 的归一化互相关。
 * 在 [Dispatchers.Default] 上运行，不阻塞 streaming 线程。
 *
 * 互相关公式（仅在有效重叠区间内求和，无零填充）：
 *   R(lag) = Σ ch0[n] * ch1[n+lag] / sqrt(Σ ch0² * Σ ch1²)
 *   求和范围 n = 0 .. N-|lag|-1
 *
 * lag 搜索范围 ±5（16 kHz 下阵列对角线 90mm 对应的最大时延约 4.2 采样点）。
 *
 * @return 诊断字符串，包含互相关峰值、峰值 lag、判定结果
 */
suspend fun crossCorrelationTest(source: AudioSource): String = withContext(Dispatchers.Default) {
    val framesNeeded = 10
    val chunkSize = AudioConfig.CHUNK_SIZE

    // 收集 10 帧
    val frames = try {
        source.audioStream().take(framesNeeded).toList()
    } catch (e: Exception) {
        return@withContext "Cross-correlation: FAIL — audioStream() threw " +
            "${e.javaClass.simpleName}: ${e.message}"
    }

    if (frames.size < framesNeeded) {
        return@withContext "Cross-correlation: FAIL — " +
            "only ${frames.size}/$framesNeeded frames collected"
    }

    val firstFrame = frames[0]
    if (firstFrame.channels < 2 || firstFrame.data.size < 2) {
        return@withContext "Cross-correlation: FAIL — " +
            "need at least 2 channels, got ch=${firstFrame.channels} listSize=${firstFrame.data.size}"
    }

    // 累积 10 帧到两个 FloatArray（各 10240 点）
    val totalSamples = framesNeeded * chunkSize
    val ch0 = FloatArray(totalSamples)
    val ch1 = FloatArray(totalSamples)

    for (f in 0 until framesNeeded) {
        val frame = frames[f]
        val offset = f * chunkSize
        System.arraycopy(frame.data[0], 0, ch0, offset, chunkSize)
        System.arraycopy(frame.data[1], 0, ch1, offset, chunkSize)
    }

    // 计算总功率（用于归一化）
    var power0 = 0.0
    var power1 = 0.0
    for (i in 0 until totalSamples) {
        power0 += ch0[i].toDouble() * ch0[i].toDouble()
        power1 += ch1[i].toDouble() * ch1[i].toDouble()
    }

    if (power0 < 1e-12 || power1 < 1e-12) {
        return@withContext "Cross-correlation: FAIL — signal too quiet " +
            "(power0=%.2e power1=%.2e)".format(power0, power1)
    }

    val normFactor = sqrt(power0 * power1)

    // maxLag = ceil(0.09 / 340 × 16000) = ceil(4.24) = 5
    val maxLag = 5

    var peakR = -1.0
    var peakLag = 0

    for (lag in -maxLag..maxLag) {
        val absLag = if (lag >= 0) lag else -lag
        val n = totalSamples - absLag

        var cross = 0.0
        if (lag >= 0) {
            for (i in 0 until n) {
                cross += ch0[i].toDouble() * ch1[i + lag].toDouble()
            }
        } else {
            val posLag = -lag
            for (i in 0 until n) {
                cross += ch0[i + posLag].toDouble() * ch1[i].toDouble()
            }
        }

        val r = cross / normFactor
        if (r > peakR) {
            peakR = r
            peakLag = lag
        }
    }

    val verdict = when {
        peakR >= 0.999 -> "FAIL: channels are identical copies (r=%.4f)".format(peakR)
        peakR >= 0.99  -> "WARN: channels nearly identical (r=%.4f), investigate USB channel extraction".format(peakR)
        else           -> "PASS"
    }

    "Cross-correlation: peak=%.4f at lag=$peakLag (expected |lag|≤$maxLag) → $verdict".format(peakR)
}
