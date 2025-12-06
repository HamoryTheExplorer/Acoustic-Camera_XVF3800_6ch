package com.example.acousticcamera.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
        val sampleRate = 44100
        // 每个数据块的大小 (Chunk Size)
        // 4096 个点约等于 0.09秒 (4096/44100)，意味着 FPS 约为 10帧/秒，既流畅又不卡
        val chunkSize = 4096
        val speedOfSound = 340.0
        val frequency = 3500.0 // 3500Hz 高频信号

        var globalSampleIndex = 0L // 全局时间指针，保证正弦波相位连续
        var angle = 0.0 // 声源旋转角度

        // 模拟无限循环，直到外部协程被取消
        while (true) {
            // --- 1. 让声源动起来 ---
            // 假设声源在 Z=1m 平面上，绕着中心 (0,0) 画圆，半径 0.6m
            val radius = 0.6
            // 每次移动一点点角度 (0.1弧度)
            angle += 0.1
            val sourceX = (radius * cos(angle)).toFloat()
            val sourceY = (radius * sin(angle)).toFloat()
            val movingSourcePos = Point3D(sourceX, sourceY, 1.0f)

            // --- 2. 生成当前时刻的音频块 ---
            val mics = MicArrayConfig.mics
            val channelDataList = ArrayList<FloatArray>()

            for (mic in mics) {
                val samples = FloatArray(chunkSize)
                // 计算该麦克风到声源的距离
                val dist = MicArrayConfig.distance(mic, movingSourcePos)
                val delaySeconds = dist / speedOfSound

                for (i in 0 until chunkSize) {
                    // 这里的 t 必须加上 globalSampleIndex，否则每块的波形都会从 0 开始，导致相位断裂
                    // globalSampleIndex 确保了正弦波在切片之间是连贯的。
                    val currentGlobalIndex = globalSampleIndex + i
                    val t = currentGlobalIndex.toDouble() / sampleRate

                    val timeWithDelay = t - delaySeconds

                    // 加点随机噪音
                    val noise = (Math.random() - 0.5) * 0.005
                    val signal = sin(2 * PI * frequency * timeWithDelay) + noise

                    samples[i] = signal.toFloat()
                }
                channelDataList.add(samples)
            }

            // --- 3. 发送数据 ---
            val audioData = AudioData(sampleRate, mics.size, channelDataList)
            emit(audioData) // 把这块肉扔给 ViewModel

            // --- 4. 更新状态与流控 ---
            globalSampleIndex += chunkSize

            // 采样时间间隔(根据实际参数调整)
            // 4096点 / 44100Hz ≈ 92ms
            delay(10)
        }
    }
}