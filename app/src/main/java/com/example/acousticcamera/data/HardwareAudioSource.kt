package com.example.acousticcamera.data

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/**
 * 硬件麦克风音频源 — 标准 2 通道立体声 (CHANNEL_IN_STEREO)。
 *
 * 兼容 Android 模拟器、内置麦克风、普通 USB 麦克风。
 * 若需 XVF3800 多通道阵列，请使用 [UsbAudioSource]（JNI 直连）。
 *
 * ─── 参数配置 ────────────────────────────────────────────────────────
 *  所有关键参数统一在 [AudioConfig] 修改。
 *
 * ─── 权限 ────────────────────────────────────────────────────────────
 *  需要在 AndroidManifest.xml 声明 RECORD_AUDIO，并在运行时完成权限授权。
 */
class HardwareAudioSource(
    private val preferredDeviceId: Int? = null,
    private val appContext: android.content.Context? = null
) : AudioSource {

    private val tag = "HardwareAudioSource"

    private val channelCount = 2
    private val micCount     = AudioConfig.MIC_COUNT_HW_STANDARD
    private val sampleRate   = AudioConfig.SAMPLE_RATE_HW
    private val chunkSize    = AudioConfig.CHUNK_SIZE

    // 交织缓冲区大小（帧数 × 通道数）
    private val interleaveBufferSize = chunkSize * channelCount

    private var audioRecord: AudioRecord? = null
    private val released = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 查找 USB 输入设备，未找到返回 null。
     * 若指定了 [preferredDeviceId] 则精确匹配，否则自动选择第一个 USB 设备。
     */
    private fun findUsbInputDevice(): AudioDeviceInfo? {
        if (appContext == null) return null
        val am = appContext.getSystemService(android.content.Context.AUDIO_SERVICE)
                as android.media.AudioManager
        val devices = am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
        return when {
            preferredDeviceId != null -> {
                val dev = devices.firstOrNull { it.id == preferredDeviceId }
                if (dev == null) Log.w(tag, "未找到指定设备 id=$preferredDeviceId")
                dev
            }
            else -> {
                val usb = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE }
                if (usb != null) {
                    Log.i(tag, "自动检测到 USB 输入设备: ${usb.productName} (id=${usb.id})")
                } else {
                    Log.w(tag, "未检测到 USB 输入设备，使用系统默认")
                }
                usb
            }
        }
    }

    /** 解析录音源。优先使用 UNPROCESSED；若设备不支持则回退到 MIC（模拟器常见）。 */
    private fun resolveAudioSource(): Int {
        val configured = AudioConfig.ANDROID_AUDIO_SOURCE
        if (configured != MediaRecorder.AudioSource.UNPROCESSED) return configured
        val ctx = appContext ?: return MediaRecorder.AudioSource.MIC
        val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE)
                as android.media.AudioManager
        val supports = am.getProperty(android.media.AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        if (!supports) {
            Log.w(tag, "设备不支持 UNPROCESSED 音频源，回退到 MIC")
            return MediaRecorder.AudioSource.MIC
        }
        return MediaRecorder.AudioSource.UNPROCESSED
    }

    @SuppressLint("MissingPermission")
    private fun buildAudioRecord(): AudioRecord {
        val usbDevice = findUsbInputDevice()
        val audioSource = resolveAudioSource()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val minBufBytes = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(interleaveBufferSize * 2 * 2)

        val record = AudioRecord.Builder()
            .setAudioSource(audioSource)
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBufBytes)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            error("AudioRecord 初始化失败！\n" +
                "请确认：\n" +
                "  · RECORD_AUDIO 权限已授予\n" +
                "  · 设备未被其他应用占用麦克风")
        }

        if (usbDevice != null) {
            val ok = record.setPreferredDevice(usbDevice)
            Log.i(tag, "优先设备: ${usbDevice.productName} (id=${usbDevice.id}) → 设置${if (ok) "成功" else "失败"}")
            Log.w(tag,
                "USB 设备 \"${usbDevice.productName}\" 可能为多通道阵列。" +
                "标准模式仅捕获前 2 通道（可能为处理后音频，非原始麦克风数据）。" +
                "若需原始麦克风数据，请切换至 XVF3800 模式。")
        }

        Log.i(tag, "AudioRecord 就绪：2ch @ ${sampleRate}Hz，chunk=${chunkSize}，bufSize=${minBufBytes}B")
        return record
    }

    /**
     * 返回连续硬件音频流 — 2 通道立体声交织 → 解交织，Float 归一化至 [-1.0, 1.0]。
     */
    @SuppressLint("MissingPermission")
    override fun audioStream(): Flow<AudioData> = flow {
        val record = buildAudioRecord().also { audioRecord = it }
        record.startRecording()

        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            audioRecord = null
            error("AudioRecord 启动录音失败！录音状态: ${record.recordingState}")
        }

        val interleavedShorts = ShortArray(interleaveBufferSize)
        var consecutiveErrors = 0

        try {
            while (currentCoroutineContext().isActive) {
                val readResult = record.read(interleavedShorts, 0, interleaveBufferSize)

                if (readResult <= 0) {
                    consecutiveErrors++
                    if (consecutiveErrors > 10) {
                        error("AudioRecord 连续 $consecutiveErrors 次读取失败 (错误码: $readResult)，设备可能已断开")
                    }
                    Log.w(tag, "AudioRecord.read 返回 $readResult (连续错误 #$consecutiveErrors)，跳过")
                    delay(50)
                    continue
                }
                consecutiveErrors = 0

                val actualFrames = readResult / channelCount

                // 立体声解交织: [L0,R0, L1,R1, ...]
                val channelData = Array(micCount) { FloatArray(actualFrames) }
                for (frame in 0 until actualFrames) {
                    val baseIdx = frame * channelCount
                    for (ch in 0 until micCount) {
                        channelData[ch][frame] = interleavedShorts[baseIdx + ch] / 32768f
                    }
                }

                emit(AudioData(
                    sampleRate = sampleRate,
                    channels   = micCount,
                    data       = channelData.toList()
                ))
            }
        } finally {
            if (released.compareAndSet(false, true)) {
                record.stop()
                record.release()
                audioRecord = null
                Log.i(tag, "AudioRecord 已停止并释放")
            } else {
                audioRecord = null
                Log.i(tag, "AudioRecord 已被 release() 释放，跳过 finally 清理")
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        Log.i(tag, "AudioRecord 通过 release() 释放")
    }
}
