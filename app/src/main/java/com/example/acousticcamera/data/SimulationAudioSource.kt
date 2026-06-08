package com.example.acousticcamera.data

import kotlinx.coroutines.flow.Flow

/**
 * 仿真音频源
 * 代理 [AudioRepository.simulateContinuousData]，使其符合 [AudioSource] 接口。
 */
class SimulationAudioSource : AudioSource {
    override fun audioStream(): Flow<AudioData> =
        AudioRepository.simulateContinuousData()
}
