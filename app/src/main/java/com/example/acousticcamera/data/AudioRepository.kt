package com.example.acousticcamera.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 生成仿真音频数据
 */

object AudioRepository {

    /**
     * 生成连续的仿真数据流
     * @return Flow<AudioData> 每隔一定时间吐出一个数据包
     */
    fun simulateContinuousData(): Flow<AudioData> = flow {
        val sampleRate    = AudioConfig.SAMPLE_RATE_SIM
        val chunkSize     = AudioConfig.CHUNK_SIZE
        // 与实际音频帧时长对齐，避免产生远超消费速度的帧并触发大量 GC
        val chunkDelayMs  = chunkSize.toLong() * 1000L / sampleRate  // 1024/16000 = 64ms
        val speedOfSound  = 340.0
        val freq = 3000.0

        val TWO_PI    = 2.0 * PI
        val phaseStep = (TWO_PI * freq / sampleRate).toFloat()

        val mics  = MicArrayConfig.mics
        val nMics = mics.size

        // 预分配 3 组缓冲区轮询复用：
        //   conflate() 同时最多 2 帧在途（1 个消费中 + 1 个等待），第 3 组始终空闲供写入，无竞争。
        //   chunkDelayMs = 64ms，略大于 DAS 单帧耗时，3 缓冲安全。
        val bufPool = Array(3) { Array(nMics) { FloatArray(chunkSize) } }
        var poolIdx = 0

        // 本地 Random 实例，替换 Math.random() 的共享 AtomicLong
        val rng = Random()

        var globalSampleIndex = 0L
        var angle = 0.0

        while (true) {
            // --- 1. 声源绕圆运动 ---
            angle += 0.1
            if (angle >= TWO_PI) angle -= TWO_PI
            val sourceX = (0.6 * cos(angle)).toFloat()
            val sourceY = (0.6 * sin(angle)).toFloat()
            val movingSourcePos = Point3D(sourceX, sourceY, GridConfig.Z_DISTANCE)

            // --- 2. 填充预分配缓冲区（无新 FloatArray 分配）---
            val channelArrays = bufPool[poolIdx % 3]
            poolIdx++

            for (micIdx in mics.indices) {
                val mic     = mics[micIdx]
                val samples = channelArrays[micIdx]
                val dist    = MicArrayConfig.distance(mic, movingSourcePos)
                val delaySeconds = dist / speedOfSound
                val timeBase = globalSampleIndex.toDouble() / sampleRate - delaySeconds

                var phase = ((TWO_PI * freq * timeBase) % TWO_PI).toFloat()
                if (phase < 0f) phase += TWO_PI.toFloat()

                for (i in 0 until chunkSize) {
                    val noise = (rng.nextDouble() - 0.5) * 0.005
                    samples[i] = (sin(phase) + noise).toFloat()
                    phase += phaseStep
                    if (phase >= TWO_PI.toFloat()) phase -= TWO_PI.toFloat()
                }
            }

            // --- 3. 发射（toList() 仅复制 nMics 个引用，非 FloatArray 内容）---
            emit(AudioData(sampleRate, nMics, channelArrays.toList()))

            // --- 4. 与实际帧率对齐，防止生产者远超消费者 ---
            globalSampleIndex += chunkSize
            delay(chunkDelayMs)
        }
    }
}
