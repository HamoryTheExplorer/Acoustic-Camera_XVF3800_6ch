package com.example.acousticcamera.data

import android.media.MediaRecorder

/**
 * ╔══════════════════════════════════════════════════════════════╗
 * ║          全局参数配置 — 修改这里适配不同麦克风阵列           ║
 * ╚══════════════════════════════════════════════════════════════╝
 *
 * 当前支持硬件：XMOS XVF3800（USB Audio Class 2.0）
 *
 * ─── USB 6 通道布局（XVF3800 固件固定输出）────────────────────
 *
 *  Channel 0 — Conference（会议模式处理后音频）
 *  Channel 1 — ASR（语音识别处理后音频）
 *  Channel 2 — Mic 0 原始数据   ◄── 波束成形使用
 *  Channel 3 — Mic 1 原始数据   ◄── 波束成形使用
 *  Channel 4 — Mic 2 原始数据   ◄── 波束成形使用
 *  Channel 5 — Mic 3 原始数据   ◄── 波束成形使用
 *
 * ════════════════════════════════════════════════════════════════
 *  参数速查表
 * ════════════════════════════════════════════════════════════════
 *
 *  ┌─────────────┬──────────────────┬──────────────────┬──────────────────┐
 *  │   参数      │   硬件模式(标准)  │   XVF3800模式     │   仿真模式        │
 *  ├─────────────┼──────────────────┼──────────────────┼──────────────────┤
 *  │ USB总通道   │ 2                │ USB_CH = 6       │ N/A              │
 *  │ 有效麦克风  │ MIC_COUNT_HW_2CH │ MIC_COUNT_HW = 4 │ MIC_COUNT_SIM = 4│
 *  │ 采样率      │ SAMPLE_RATE_HW   │ SAMPLE_RATE_HW   │ SAMPLE_RATE_SIM  │
 *  │ 每帧采样点  │ CHUNK_SIZE = 1024│ CHUNK_SIZE = 1024│ CHUNK_SIZE = 1024│
 *  │ 帧时长      │ 64ms             │ 64ms             │ 64ms             │
 *  │ 帧率        │ ≈ 15.6 FPS       │ ≈ 15.6 FPS       │ ≈ 15.6 FPS       │
 *  │ 阵列布局    │ 方形 (63.6mm)   │ 方形 (63.6mm)   │ 方形 (63.6mm)   │
 *  │ 阵列坐标    │ MicArrayConfig   │ MicArrayConfig   │ MicArrayConfig   │
 *  │ 录音源      │ UNPROCESSED       │ UNPROCESSED       │ N/A              │
 *  │ 声源        │ 真实环境(2ch)     │ 真实环境(XVF3800) │ 3000Hz单频       │
 *  └─────────────┴──────────────────┴──────────────────┴──────────────────┘
 *
 * ⚠️ 修改 MIC_COUNT_* 后，必须同步更新 MicArrayConfig.kt
 *    中的麦克风物理坐标，否则波束成形结果将不正确。
 */
object AudioConfig {

    // ────────────────────────────────────────────────────────────
    //  阵列参数
    // ────────────────────────────────────────────────────────────

    /** USB 设备实际输出的交织通道总数 (XVF3800 固件固定 6 通道) */
    const val TOTAL_USB_CHANNELS: Int = 6

    /** 硬件模式有效麦克风通道数 (从 USB Channel 2-5 提取) */
    const val MIC_COUNT_HW: Int = 4

    /** 标准硬件模式通道数 (兼容内置麦克风和仿真器，通常为 MONO 或 STEREO) */
    const val MIC_COUNT_HW_STANDARD: Int = 2

    /** 仿真模式麦克风通道数 (需与 MicArrayConfig.mics.size 一致) */
    const val MIC_COUNT_SIM: Int = 4

    // ────────────────────────────────────────────────────────────
    //  采样参数
    // ────────────────────────────────────────────────────────────

    /**
     * 【硬件】采样率 (Hz)
     * 统一 16000 Hz。
     */
    const val SAMPLE_RATE_HW: Int = 16_000

    /** 【仿真】采样率 (Hz) */
    const val SAMPLE_RATE_SIM: Int = 16_000

    /**
     * 每帧采样点数（硬件与仿真共用）
     * 耗时 ≈ CHUNK_SIZE / SAMPLE_RATE 秒
     *   1024 / 16000 = 64ms → ~15.6 FPS
     */
    const val CHUNK_SIZE: Int = 1_024

    // ────────────────────────────────────────────────────────────
    //  Android AudioRecord 参数
    // ────────────────────────────────────────────────────────────

    /**
     * Android 录音源类型。
     * UNPROCESSED = 不做 AEC/NS 等后处理，适合阵列算法。
     * 若设备不支持 UNPROCESSED，可改为 MIC。
     */
    val ANDROID_AUDIO_SOURCE: Int = MediaRecorder.AudioSource.UNPROCESSED

    // ────────────────────────────────────────────────────────────
    //  USB 设备标识（用于日志过滤，不影响录音逻辑）
    // ────────────────────────────────────────────────────────────

    /** XVF3800 USB Vendor ID（六通道固件实测值） */
    const val XVF3800_VENDOR_ID: Int  = 0x2886
    /** XVF3800 USB Product ID（六通道固件实测值） */
    const val XVF3800_PRODUCT_ID: Int = 0x001A
}
