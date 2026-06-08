package com.example.acousticcamera.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import java.io.File
import java.io.FileOutputStream

/**
 * JNI 回调基类 — native streaming 线程通过此接口将 PCM 数据传回 Kotlin。
 *
 * 每个回调在 C 层 pthread 中调用（已 AttachCurrentThread），
 * frameCount 固定为 [AudioConfig.CHUNK_SIZE] (1024)，除最后一帧可能不满。
 *
 * ⚠ 使用 abstract class 而非 interface：
 *   JNI GetMethodID 无法找到 Kotlin interface 的默认方法。
 *   abstract class 的 open 方法会被编译为普通虚方法，GetMethodID 可正确获取。
 */
abstract class NativePcmCallback {
    abstract fun onPcmData(ch0: ShortArray, ch1: ShortArray, ch2: ShortArray, ch3: ShortArray, frameCount: Int)

    /**
     * native streaming 线程遇到致命错误时回调（JNI pthread → Kotlin）。
     *
     * @param errorCode ioctl 返回值（负数 = -errno 或 ioctl rc）
     * @param message 人类可读的错误描述
     */
    open fun onStreamingError(errorCode: Int, message: String) {
        // 默认 no-op：允许调用方按需覆盖
    }

    /**
     * native 层日志回调（C pthread / JNI → Kotlin，已 AttachCurrentThread）。
     *
     * 在 __android_log_print 之外提供一条直达 App UI 的通道，
     * 适合在无 logcat 环境下调试 streaming 状态。
     *
     * @param level   0=DEBUG, 1=INFO, 2=WARN, 3=ERROR
     * @param tag     日志标签（通常为 "UsbAudioSource"）
     * @param message 日志内容（纯文本，不含换行）
     */
    open fun onNativeLog(level: Int, tag: String, message: String) {
        // 默认 no-op：允许调用方按需覆盖
    }
}

/**
 * USB 直连音频源 — 完全绕开 AudioRecord / USB Audio HAL
 *
 * 通过 [UsbManager] 获取文件描述符后，在 JNI 层用 ioctl 提交等时 URB
 * 直接读取 XVF3800 的 PCM 原始数据。
 *
 * ─── USB 6 通道布局（XVF3800 固件固定输出）────────────────────────────────
 *  Channel 0 — Conference   Channel 1 — ASR
 *  Channel 2 — Mic 0 ◄──   Channel 3 — Mic 1 ◄──
 *  Channel 4 — Mic 2 ◄──   Channel 5 — Mic 3 ◄──
 */
class UsbAudioSource(
    private val context: Context,
    private val debugSavePcm: Boolean = false
) : AudioSource {

    private val tag = "UsbAudioSource"

    /** Phase 5/6 互斥：streaming 活跃时禁止 [readOneUrb] 等单次 URB 操作 */
    @Volatile
    var isStreaming: Boolean = false
        private set

    companion object {
        private var libraryLoaded = false
        init {
            try {
                System.loadLibrary("usb_iso_transfer")
                libraryLoaded = true
            } catch (e: UnsatisfiedLinkError) {
                Log.w("UsbAudioSource", "JNI 库未编译或 ABI 不匹配: ${e.message}")
            }
        }

        /** 每次调用 requestPermission 递增，避免 PendingIntent 跨次污染 */
        private val requestCodeCounter = java.util.concurrent.atomic.AtomicInteger(0)

        /**
         * 进程内 USB 权限缓存。
         *
         * Android 原生 UsbManager.hasPermission() 在某些 OEM ROM 上不可靠
         * （权限对话框点击确定后仍返回 false）。此缓存绕过该问题。
         */
        private val grantedDeviceNames = mutableSetOf<String>()
    }

    /** JNI — 编译链验证（Phase 3 骨架），后续替换为等时传输函数 */
    private external fun nativeTestFd(fd: Int): String

    /**
     * JNI — 纯 ioctl 路径：DISCONNECT → CLAIMINTERFACE → SETINTERFACE。
     *
     * 三步均在**调用方 fd** 上执行。行为取决于该 fd 上是否有已存在的 claim：
     *   - 无任何 claim 时：DISCONNECT 返回 ENOTTY/ENODATA（可忽略），
     *     CLAIMINTERFACE 返回 0（成功），SETINTERFACE 返回 0。
     *   - 已有 claim 时（如 Android API 预先 claim 了同一 fd）：
     *     CLAIMINTERFACE 返回 EBUSY(16)，SETINTERFACE 可能仍返回 0。
     *   - 内核驱动占用且未 detach：DISCONNECT ENOTTY，CLAIMINTERFACE EBUSY。
     *
     * 本函数在步骤 8 中用于在 app fd 上完成最终的 USBFS 层面 claim，
     * 为 Phase 5 的等时 URB 提交做准备。
     */
    private external fun nativeClaimInterface(fd: Int, interfaceId: Int, altSetting: Int): String

    /**
     * JNI — 提交单个等时 URB 并读取原始数据（Phase 5，验证模式）。
     *
     * 在已通过 Android API claimInterface + setInterface 的 fd 上，
     * 提交 USBDEVFS_SUBMITURB，用 REAPURBNDELAY 非阻塞轮询等待，
     * 检查各 iso packet 状态，对有效数据做 hex dump。
     *
     * @return 完整诊断字符串（SUBMITURB rc / REAPURB status / iso status / hex dump）
     *
     * ⚠ 内部使用 REAPURBNDELAY 非阻塞轮询 + timeoutMs 超时，保证返回。
     * ⚠ Phase 5 验证模式返回诊断 String；Phase 6 连续读取时改为返回 ByteArray。
     */
    private external fun nativeReadOneUrb(
        fd: Int, endpointAddress: Int, maxPacketSize: Int, numPackets: Int,
        timeoutMs: Int
    ): String

    /**
     * JNI — 启动连续等时传输（Phase 6）。
     *
     * 在 C 层创建 pthread，执行双 URB 乒乓缓冲 + PCM 提取。
     * 提取到的 4 通道 PCM 数据通过 [NativePcmCallback] 异步回调。
     *
     * @return 0 成功，负数表示错误码
     */
    private external fun nativeStartStreaming(
        fd: Int, endpointAddress: Int, maxPacketSize: Int,
        numPackets: Int, callback: NativePcmCallback
    ): Int

    /** JNI — 停止连续等时传输，释放所有 native 资源并等待线程退出。 */
    private external fun nativeStopStreaming()

    /**
     * JNI — 原生 USB 控制传输，绕过 Android 框架的 Host→Device EPERM 限制。
     *
     * 直接调用 ioctl(fd, USBDEVFS_CONTROL, ...)，在 USBFS 内核层执行，
     * 不受 UsbDeviceConnection.controlTransfer() 的 Java 层权限检查影响。
     *
     * @param fd device fd
     * @param bmRequestType USB 请求类型
     * @param bRequest USB 请求码
     * @param wValue wValue
     * @param wIndex wIndex
     * @param data 数据载荷（OUT: 要发送的数据; IN: 缓冲区，会被填充）
     * @param timeout 超时毫秒
     * @return OUT: 成功时返回写入字节数，失败返回 -errno
     *         IN:  成功时返回读取字节数（数据已写入 data），失败返回 -errno
     */
    private external fun nativeSendControl(
        fd: Int, bmRequestType: Int, bRequest: Int,
        wValue: Int, wIndex: Int, data: ByteArray, timeout: Int
    ): Int

    /** 查询该设备是否有 USB 权限（系统判断 + 进程内缓存） */
    fun hasUsbPermission(device: UsbDevice): Boolean =
        usbManager.hasPermission(device) || grantedDeviceNames.contains(device.deviceName)

    /**
     * Phase 3 编译链验证。
     *
     * 返回值语义：
     * - `"JNI not built: ..."` → .so 未编译，编译链未建立
     * - `"JNI OK, ioctl error: ..."` → native 函数调用成功，但 ioctl 失败
     * - `"fd=XX, devnum=XX, slow=XX"` → JNI + ioctl 全部正常
     */
    fun verifyJniChain(fd: Int): String {
        if (!libraryLoaded) {
            return "JNI not built: 请确认已在 build.gradle.kts 添加 NDK 配置"
        }
        val raw = nativeTestFd(fd)
        return if (raw.startsWith("fd=")) {
            raw  // ioctl 成功：fd=XX, devnum=XX, slow=XX
        } else {
            "JNI OK, ioctl error: $raw"
        }
    }

    /**
     * 纯 JNI ioctl 路径 — 仅在桌面 Linux 或支持 USBDEVFS_DISCONNECT 的内核上有效。
     *
     * 在 Android 上此函数仅用于诊断输出；实际接口接管必须用 [claimInterface]。
     */
    fun claimInterfaceNative(fd: Int, interfaceId: Int, altSetting: Int): String {
        if (!libraryLoaded) {
            return "claimInterfaceNative: JNI 库未加载"
        }
        return nativeClaimInterface(fd, interfaceId, altSetting)
    }

    /**
     * Phase 5 — 提交单个等时 URB，返回完整诊断字符串（验证模式）。
     *
     * 调用前必须已完成：
     *   1. [openConnection] — 已持有 [UsbDeviceConnection]
     *   2. [claimInterface] — 已通过 Android API claim + setInterface
     *
     * @param fd [UsbDeviceConnection.getFileDescriptor]
     * @param endpointAddress 等时 IN 端点地址（来自 [StreamingEndpoint] 或 [UsbStreamingConfig]）
     * @param maxPacketSize 端点 wMaxPacketSize
     * @param numPackets 单次 URB 包含的等时包数量，默认 8（8ms 数据 @ 1kHz 包速率）
     * @param timeoutMs native 层 REAPURBNDELAY 轮询超时，默认 4000ms
     * @return 诊断字符串，可直接显示在 App UI。包含：
     *         SUBMITURB rc / REAPURB ioctl rc / urb->status /
     *         各 iso packet 的 status & actual_length / hex dump
     */
    suspend fun readOneUrb(
        fd: Int,
        endpointAddress: Int,
        maxPacketSize: Int,
        numPackets: Int = 8,
        timeoutMs: Int = 4000
    ): String {
        if (!libraryLoaded) {
            return "readOneUrb: JNI 库未加载\n请确认已在 build.gradle.kts 添加 NDK 配置"
        }
        if (isStreaming) {
            return "readOneUrb: FAIL — Phase 6 streaming 正在运行\n请先停止 streaming 再执行单次 URB 测试"
        }
        return withContext(Dispatchers.IO) {
            nativeReadOneUrb(fd, endpointAddress, maxPacketSize, numPackets, timeoutMs)
        }
    }

    /**
     * Phase 5b — 解除所有 Feature Unit 的静音（UAC 2.0 SET_CUR 控制传输）。
     *
     * 通过 Android [UsbDeviceConnection.controlTransfer] 发送 UAC 2.0
     * SET_CUR 请求，将每个支持 Mute 的 Feature Unit 的 Mute 控制设为 0（取消静音）。
     *
     * 调用前必须先运行 [dumpUsbDescriptors] 以填充 [featureUnits]。
     *
     * @param connection 已打开的 USB 连接
     * @return 每步结果的诊断字符串（多行）
     */
    suspend fun unmuteAllFeatureUnits(connection: UsbDeviceConnection): String = withContext(Dispatchers.IO) {
        val uacVer = uacProtocolVersion
        val acIfNum = acInterfaceNumber

        if (featureUnits.isEmpty()) {
            return@withContext "⚠ 未找到任何 Feature Unit。\n" +
                "   请确保已先运行 dumpUsbDescriptors() 解析描述符。\n" +
                "   如果仍有 hex dump 全零，可能是设备端静音/无麦克风连接。"
        }

        val mutes = featureUnits.filter { it.hasMute }
        if (mutes.isEmpty()) {
            return@withContext "⚠ 找到 ${featureUnits.size} 个 Feature Unit，但均不支持 Mute 控制。\n" +
                "   Unit IDs: ${featureUnits.map { it.unitId }}"
        }

        val sb = StringBuilder()
        val verStr = if (uacVer == UAC_PROTO_V2) "UAC 2.0" else "UAC 1.0"
        sb.appendLine("UAC $verStr, ${featureUnits.size} 个 Feature Unit(s)，其中 ${mutes.size} 个支持 Mute:")

        if (acIfNum < 0) {
            sb.appendLine("⚠ 未检测到 AC Interface，所有 unmute 可能失败")
        }
        sb.appendLine("  AC Interface bNum=$acIfNum, wIndex 低字节=$acIfNum")
        sb.appendLine("  Feature Units: ${mutes.map { it.unitId }} (支持 Mute)")
        sb.appendLine("  数据格式: ${if (uacVer == UAC_PROTO_V2) "2 bytes (UAC 2.0)" else "1 byte (UAC 1.0)"}")

        // 逐 FU 执行 GET_CUR + SET_CUR
        for (fu in mutes) {
            sb.appendLine("  ── FU ${fu.unitId} (sourceId from descriptor, AC If=$acIfNum) ──")
            val result = diagnoseFeatureUnit(connection, fu.acInterfaceNum, fu.unitId, uacVer)
            sb.appendLine("    $result")
        }

        sb.toString()
    }

    /**
     * 探测 UAC 2.0 Clock Source 状态（GET_CUR，Device→Host，不需要 bypass）。
     *
     * 读取 Clock Validity (CS=0x02): 0=invalid, 1=valid
     * 读取 Cur Sample Frequency (CS=0x01)
     *
     * 如果 Clock Valid=0，设备不会产出音频 → hex dump 全零。
     */
    fun probeClockStatus(connection: UsbDeviceConnection): String {
        if (clockSources.isEmpty()) {
            return "⚠ 未找到 Clock Source（非 UAC 2.0 设备?）"
        }
        val sb = StringBuilder()
        for (cs in clockSources) {
            val wIndex = (cs.clockId shl 8) or cs.acInterfaceNum

            // 探测 Cur Freq: 尝试 bReq=0x81/0x01 × wLen=4/2/1
            var freqOk = false
            val bReqs = intArrayOf(0x81, 0x01)
            val wLens = intArrayOf(4, 2, 1)
            for (bReq in bReqs) {
                if (freqOk) break
                for (wLen in wLens) {
                    if (freqOk) break
                    val fb = ByteArray(wLen)
                    val fr = connection.controlTransfer(0xA1, bReq, 0x0100, wIndex, fb, wLen, 1000)
                    if (fr >= 1) {
                        freqOk = true
                        val freq = if (fr >= 4)
                            ((fb[3].toInt() and 0xFF) shl 24) or
                            ((fb[2].toInt() and 0xFF) shl 16) or
                            ((fb[1].toInt() and 0xFF) shl 8) or
                            (fb[0].toInt() and 0xFF)
                        else if (fr >= 2)
                            ((fb[1].toInt() and 0xFF) shl 8) or (fb[0].toInt() and 0xFF)
                        else fb[0].toInt() and 0xFF
                        sb.appendLine("  CurFreq: $freq Hz")
                    }
                }
            }
            if (!freqOk) {
                sb.appendLine("  CurFreq: probe failed")
            }

            // Clock Valid (1 byte, CS=0x02)
            if (cs.hasValidControl) {
                val vb = ByteArray(1)
                val vr = connection.controlTransfer(0xA1, 0x81, 0x0200, wIndex, vb, 1, 1000)
                if (vr >= 1) {
                    val valid = vb[0].toInt() and 0xFF
                    sb.appendLine("  ClockValid: " + (if (valid == 0) "INVALID ← 全零根因" else "valid"))
                } else {
                    sb.appendLine("  ClockValid: rc=$vr")
                }
            }
        }
        return sb.toString()
    }

    /**
     * 发送单次 UAC 2.0 SET_CUR 请求，将 Feature Unit 的 Mute 控制设为 0。
     *
     * USB setup packet 格式：
     *   bmRequestType = 0x21 (Host→Device, Class, Interface)
     *   bRequest      = 0x01 (SET_CUR)
     *   wValue        = (MUTE_CONTROL << 8) | master_channel(0) = 0x0100
     *   wIndex        = (UnitID << 8) | InterfaceNumber
     *   数据           = [0x00] （0=unmuted, 1=muted）
     *
     * @return true 表示 controlTransfer 成功（返回 >= 0 字节）
     */
    /**
     * 单 Feature Unit 诊断：GET_CUR Mute + GET_CUR Vol + SET_CUR Mute(JNI)
     *
     * 返回值格式: "Mute=0(unmuted)  Vol=512=2.0dB  SET_Mute=rc=2  (wIndex=0x1200)"
     *
     * rc 含义（精确值，不映射为标签）:
     *   >=0   — 传输字节数
     *   -1    — EPERM（内核拒绝 Host→Device）
     *   -32   — EPIPE/STALL（设备拒绝该控制）
     *   -22   — EINVAL（参数无效）
     *   -100  — JNI 未加载
     *
     * ⚠ 验证方式：看 rc 的精确数字，不要依赖标签。
     */
    private fun diagnoseFeatureUnit(
        connection: UsbDeviceConnection,
        acInterfaceNum: Int,
        unitId: Int,
        uacVersion: Int
    ): String {
        val bmReqGet = 0xA1       // Device→Host | Class | Interface
        val bmReqSet = 0x21       // Host→Device | Class | Interface
        val bReqGet  = 0x81       // GET_CUR
        val bReqSet  = 0x01       // SET_CUR
        val wIndex   = (unitId shl 8) or acInterfaceNum
        val sz       = if (uacVersion == UAC_PROTO_V2) 2 else 1

        // ─ 1. GET_CUR Mute (wValue = 0x0100) ─
        val mb = ByteArray(sz)
        val mr = connection.controlTransfer(bmReqGet, bReqGet, 0x0100, wIndex, mb, sz, 1000)
        val muteStr = if (mr in 1..sz) {
            val v = if (sz == 2) ((mb[1].toInt() and 0xFF) shl 8) or (mb[0].toInt() and 0xFF) else mb[0].toInt() and 0xFF
            if (v == 0) "Mute=0(unmuted)" else "Mute=$v(MUTED!)"
        } else "Mute=rc=$mr"

        // ─ 2. GET_CUR Volume (wValue = 0x0200) ─
        val vb = ByteArray(sz)
        val vr = connection.controlTransfer(bmReqGet, bReqGet, 0x0200, wIndex, vb, sz, 1000)
        val volStr = if (vr in 1..sz) {
            val v = if (sz == 2) ((vb[1].toInt() and 0xFF) shl 8) or (vb[0].toInt() and 0xFF) else vb[0].toInt() and 0xFF
            val db = if (uacVersion == UAC_PROTO_V2) "=%.1fdB".format(v / 256.0) else ""
            if (v == 0) "Vol=0$db ←全零根因!" else "Vol=$v$db"
        } else "Vol=rc=$vr"

        // ─ 3. SET_CUR Mute=0 via JNI (wValue = 0x0100, data = [0,0] for UAC2) ─
        val data = if (uacVersion == UAC_PROTO_V2) byteArrayOf(0, 0) else byteArrayOf(0)
        val fd = connection.fileDescriptor
        val sr = if (libraryLoaded) nativeSendControl(fd, bmReqSet, bReqSet, 0x0100, wIndex, data, 1000) else -100
        val setStr = "SET_Mute=rc=$sr"

        return "$muteStr  $volStr  $setStr  (wIndex=0x%04X)".format(wIndex)
    }

    /**
     * Phase 4 — 接管 USB 音频流接口（Android API 路径，实际工作路径）。
     *
     * Android 内核不支持应用层 [USBDEVFS_DISCONNECT] ioctl（返回 ENOTTY）。
     * 因此使用 Android 框架层的 [UsbDeviceConnection.claimInterface] 并传入
     * force=true。USB 服务（system_server）以 root 权限执行内核驱动 detach，
     * 然后 claim + setInterface。这之后 JNI 层可在此基础上提交等时 URB。
     *
     * 调用前必须确保没有在 Kotlin 层预先调用 [UsbDeviceConnection.claimInterface]
     * （否则 force=true 无意义，因为接口已被本进程持有）。
     */
    suspend fun claimInterface(connection: UsbDeviceConnection, usbInterface: UsbInterface): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()

        // Step 1: Android API claimInterface(force=true)
        // force=true → 以 system_server 权限执行内核驱动 detach
        val claimOk = connection.claimInterface(usbInterface, true)
        sb.appendLine("Android API claimInterface(force=true): ${if (claimOk) "OK" else "FAIL"}")

        if (!claimOk) {
            sb.appendLine("-> FAIL: Android API claimInterface returned false")
            return@withContext sb.toString()
        }

        // Step 2: Android API setInterface → 切换到操作 alt setting
        val setOk = connection.setInterface(usbInterface)
        sb.appendLine("Android API setInterface(alt=${usbInterface.alternateSetting}): ${if (setOk) "OK" else "FAIL"}")

        if (!setOk) {
            sb.appendLine("-> FAIL: Android API setInterface returned false")
            return@withContext sb.toString()
        }

        sb.appendLine()
        sb.appendLine("SUCCESS: interface ${usbInterface.id} claimed, alt setting ${usbInterface.alternateSetting} active")
        sb.toString()
    }

    /**
     * 确保 app fd 持有 USBFS interface claim（而非依赖 system_server 的 claim）。
     *
     * Android 的 [UsbDeviceConnection.claimInterface] 通过 system_server 完成
     * 内核驱动 detach，但 claim 本身在 system_server 的 fd 上。若 system_server
     * 因超时/重启/抢占释放 claim，app fd 上的 URB 提交会失败。
     *
     * 本函数在 app fd 上执行 USBDEVFS_CLAIMINTERFACE：
     *   - 若直接成功（rc=0）→ app fd 持有 claim，内核驱动无法 rebind
     *   - 若 EBUSY（system_server 持有）→ 先 release system_server 的 claim，
     *     再在 app fd 上 claim
     *   - 若其他错误 → 回退到 Android API，维持 system_server claim
     *
     * @return true 表示 app fd 成功持有 claim
     */
    /**
     * 确保 app fd 持有 USBFS interface claim（suspend 版本）。
     *
     * 快速路径（nativeClaimInterface ioctl）在调用线程执行；
     * Binder 调用（releaseInterface / claimInterface）切换到 [Dispatchers.IO]。
     */
    suspend fun ensureAppFdClaim(connection: UsbDeviceConnection, usbInterface: UsbInterface): Boolean {
        val fd = connection.fileDescriptor
        val ifNum = usbInterface.id
        val alt = usbInterface.alternateSetting

        // 先尝试直接在 app fd 上 claim（fast ioctl）
        val firstTry = nativeClaimInterface(fd, ifNum, alt)
        if (firstTry.contains("CLAIMINTERFACE: rc=0") &&
            firstTry.contains("SETINTERFACE: rc=0")) {
            Log.i(tag, "ensureAppFdClaim: app fd 已持有 interface $ifNum claim")
            return true
        }

        // EBUSY → system_server 已持有，需要先释放
        if (firstTry.contains("errno=16")) {
            Log.i(tag, "ensureAppFdClaim: system_server 持有 claim，释放中...")
            return withContext(Dispatchers.IO) {
                try {
                    connection.releaseInterface(usbInterface)
                } catch (e: Exception) {
                    Log.w(tag, "ensureAppFdClaim: releaseInterface 异常: ${e.message}")
                }

                // 立即在 app fd 上 claim（窗口期内核驱动可能 rebind，但概率极低）
                val secondTry = nativeClaimInterface(fd, ifNum, alt)
                if (secondTry.contains("CLAIMINTERFACE: rc=0") &&
                    secondTry.contains("SETINTERFACE: rc=0")) {
                    Log.i(tag, "ensureAppFdClaim: 已迁移 claim 到 app fd")
                    return@withContext true
                }

                // 回退：重新通过 Android API claim
                Log.w(tag, "ensureAppFdClaim: app fd claim 失败，回退到 system_server")
                try {
                    connection.claimInterface(usbInterface, true)
                    connection.setInterface(usbInterface)
                } catch (e: Exception) {
                    Log.e(tag, "ensureAppFdClaim: 回退 claim 异常: ${e.message}")
                    return@withContext false
                }
                Log.w(tag, "ensureAppFdClaim: 未能在 app fd 上 claim，streaming 可能不稳定")
                return@withContext false
            }
        }

        Log.w(tag, "ensureAppFdClaim: 未能在 app fd 上 claim，streaming 可能不稳定")
        return false
    }

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    /** 当前已发现的 XVF3800 设备 */
    private var usbDevice: UsbDevice? = null

    /**
     * 已建立的 USB 连接（后续通过 getFileDescriptor() 传 fd 给 JNI）。
     *
     * TODO Phase 3: 注册 UsbManager.ACTION_USB_DEVICE_DETACHED 广播，
     *   在设备被物理拔出时自动置 null 并关闭 fd，避免后续 ioctl 在 stale fd
     *   上操作导致 ENODEV 或 native crash。
     */
    private var usbConnection: UsbDeviceConnection? = null

    /** 等时输入端点信息（后续 JNI 层需要 interface number + endpoint address） */
    data class StreamingEndpoint(
        val usbInterface: UsbInterface,
        val interfaceNumber: Int,
        val alternateSetting: Int,
        val endpointAddress: Int,
        val maxPacketSize: Int,
        val interval: Int
    )

    private var streamingEndpoint: StreamingEndpoint? = null

    /** 原始描述符解析结果（controlTransfer 路径，与 Android API 互补验证） */
    data class UsbStreamingConfig(
        val interfaceId: Int,
        val alternateSetting: Int,
        val endpointAddress: Int,
        val maxPacketSize: Int
    )

    /** Audio Control Interface 中发现的 Feature Unit 信息 */
    data class FeatureUnitInfo(
        val acInterfaceNum: Int,
        val unitId: Int,
        val hasMute: Boolean,
        val hasVolume: Boolean
    )

    /** UAC 2.0 Clock Source 信息 */
    data class ClockSourceInfo(
        val acInterfaceNum: Int,
        val clockId: Int,
        val hasFreqControl: Boolean,    // CS_SAM_FREQ_CONTROL
        val hasValidControl: Boolean    // CS_CLOCK_VALID_CONTROL
    )

    private var parsedConfig: UsbStreamingConfig? = null
    private var featureUnits: List<FeatureUnitInfo> = emptyList()
    private var clockSources: List<ClockSourceInfo> = emptyList()

    /** 解析得到的 UAC 协议版本: 0x20 = UAC 2.0, 0x00 = UAC 1.0 */
    private var uacProtocolVersion: Int = 0

    /** Audio Control Interface 的 bInterfaceNumber（用于 SET_CUR 的 wIndex） */
    private var acInterfaceNumber: Int = -1

    // ═══════════════════════════════════════════════════════════════════════
    // 1. 设备发现
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 通过 VID/PID 匹配 XVF3800。
     *
     * @return 找到的 [UsbDevice]，未找到返回 null
     */
    fun findXvf3800Device(): UsbDevice? {
        val devices = usbManager.deviceList.values

        Log.i(tag, "开始扫描 USB 设备，共 ${devices.size} 台")
        for (d in devices) {
            Log.i(tag, "  设备: ${d.deviceName}  VID=0x%04X  PID=0x%04X  class=0x%02X"
                .format(d.vendorId, d.productId, d.deviceClass))
        }

        val found = devices.firstOrNull { d ->
            d.vendorId == AudioConfig.XVF3800_VENDOR_ID &&
            d.productId == AudioConfig.XVF3800_PRODUCT_ID
        }

        usbDevice = found
        if (found != null) {
            Log.i(tag, "✅ 发现 XVF3800: ${found.deviceName}  VID=0x%04X  PID=0x%04X"
                .format(found.vendorId, found.productId))
            logDeviceInterfaces(found)
        } else {
            Log.w(tag, "❌ 未发现 XVF3800 (VID=0x%04X PID=0x%04X)"
                .format(AudioConfig.XVF3800_VENDOR_ID, AudioConfig.XVF3800_PRODUCT_ID))
        }

        return found
    }

    /**
     * 打印设备所有 interface + endpoint 信息，辅助调试。
     *
     * 说明：Android 的 UsbDevice.getInterface(i) 已经枚举了所有 AlternateSetting。
     * 每个 UsbInterface 对象对应一个特定的 (bInterfaceNumber, bAlternateSetting) 组合。
     */
    private fun logDeviceInterfaces(device: UsbDevice) {
        Log.i(tag, "XVF3800 接口+AltSetting 总数: ${device.interfaceCount}")
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            // iface.id = bInterfaceNumber, iface.alternateSetting = bAlternateSetting
            Log.i(tag, "  If[$i] bNum=${iface.id} alt=${iface.alternateSetting}" +
                "  class=0x%02X  subClass=0x%02X  protocol=0x%02X  epCount=${iface.endpointCount}"
                .format(iface.interfaceClass, iface.interfaceSubclass, iface.interfaceProtocol))

            for (epIdx in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(epIdx)
                val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                val type = endpointTypeLabel(ep.type)
                Log.i(tag, "      EP$epIdx: addr=0x%02X  dir=$dir  type=$type" +
                    "  maxPacket=${ep.maxPacketSize}  interval=${ep.interval}"
                    .format(ep.address))
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. 权限请求
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 异步请求 USB 设备权限。
     *
     * 三重判断（解决 OEM ROM 上 hasPermission() 不可靠的问题）：
     * 1. UsbManager.hasPermission() → 直接返回 true
     * 2. 进程内缓存（本次会话曾授权过该 deviceName）→ 直接返回 true
     * 3. 弹出系统对话框 → suspendCancellableCoroutine 等待结果（10s 超时）
     *
     * @return true 表示权限已授予
     */
    suspend fun requestPermission(device: UsbDevice): Boolean {
        // 1. 系统原生判断
        if (usbManager.hasPermission(device)) {
            Log.i(tag, "USB 权限已存在（系统），跳过对话框")
            return true
        }

        // 2. 进程内缓存（解决 OEM ROM 上 hasPermission 返回 false 的问题）
        if (grantedDeviceNames.contains(device.deviceName)) {
            Log.i(tag, "USB 权限已存在（进程缓存），跳过对话框")
            return true
        }

        // 3. 弹出系统对话框（必须在 Main 线程注册 BroadcastReceiver）
        Log.i(tag, "正在请求 USB 设备权限... deviceName=${device.deviceName}")
        return try {
            kotlinx.coroutines.withTimeout(10_000L) {
                withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine { continuation ->
                    val action = "com.example.acousticcamera.USB_PERMISSION"

                    // 使用唯一 requestCode，避免上一次被取消的 PendingIntent 污染
                    val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
                    } else {
                        PendingIntent.FLAG_ONE_SHOT
                    }
                    val uniqueCode = requestCodeCounter.incrementAndGet()

                    val permissionIntent = PendingIntent.getBroadcast(
                        context, uniqueCode, Intent(action), pendingFlags
                    )

                    val receiverObj = object : BroadcastReceiver() {
                        override fun onReceive(ctx: Context, intent: Intent) {
                            Log.i(tag, "USB 权限回调 onReceive 触发")

                            // 安全地取消注册（可能已被 invokeOnCancellation 取消）
                            try { ctx.unregisterReceiver(this) } catch (_: Exception) {}

                            val granted = intent.getBooleanExtra(
                                UsbManager.EXTRA_PERMISSION_GRANTED, false
                            )
                            Log.i(tag, "USB 权限结果: ${if (granted) "✅ 已授予" else "❌ 已拒绝"}")

                            if (granted) {
                                grantedDeviceNames.add(device.deviceName)
                            }
                            continuation.resume(granted)
                        }
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        context.registerReceiver(receiverObj, IntentFilter(action),
                            Context.RECEIVER_NOT_EXPORTED)
                    } else {
                        context.registerReceiver(receiverObj, IntentFilter(action))
                    }

                    usbManager.requestPermission(device, permissionIntent)

                        continuation.invokeOnCancellation {
                            Log.i(tag, "USB 权限请求已取消（协程取消）")
                            try { context.unregisterReceiver(receiverObj) } catch (_: Exception) {}
                            permissionIntent.cancel()
                        }
                    }
                }
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            Log.e(tag, "❌ USB 权限请求超时（10 秒无响应）")
            false
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. 建立连接
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 打开 USB 设备连接。
     *
     * 失败时自动清理进程内权限缓存（[grantedDeviceNames]），
     * 调用方应重新走 [requestPermission] → [openConnection] 流程。
     *
     * @return [UsbDeviceConnection]，失败返回 null
     */
    fun openConnection(device: UsbDevice): UsbDeviceConnection? {
        // 若已有连接，先关闭旧的，避免重复打开造成 fd 泄漏
        if (usbConnection != null) {
            Log.w(tag, "检测到旧的 USB 连接，先关闭再重新打开")
            try { usbConnection?.close() } catch (_: Exception) {}
            usbConnection = null
        }

        // 防御：系统实际权限可能已失效（设备拔插 / 重枚举），
        // 但进程缓存仍标记为已授权。此时 openDevice 会失败。
        if (!usbManager.hasPermission(device)) {
            Log.w(tag, "⚠ openDevice 前检测到系统权限已失效，清理进程缓存")
            grantedDeviceNames.remove(device.deviceName)
        }

        val conn = usbManager.openDevice(device)

        if (conn != null) {
            usbConnection = conn

            val fd = conn.fileDescriptor
            Log.i(tag, "✅ USB 连接已建立: ${device.deviceName}")
            Log.i(tag, "   设备 fd: $fd")

            // Phase 3: 验证 JNI 编译链 — ioctl(fd, USBDEVFS_CONNECTINFO)
            val jniResult = verifyJniChain(fd)
            Log.i(tag, "   nativeTestFd result: $jniResult")
        } else {
            Log.e(tag, "❌ openDevice() 失败: ${device.deviceName}")
            // 清理可能过期的缓存，方便下次重试
            grantedDeviceNames.remove(device.deviceName)
            Log.i(tag, "   已清理进程缓存，下次将重新弹出权限对话框")
        }

        return conn
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. 端点定位
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 遍历所有 Interface + AlternateSetting，找到 XVF3800 音频流的等时 IN 端点。
     *
     * USB Audio Class 2.0 设备的结构：
     *   - Audio Control Interface (class=0x01, subClass=0x01)：无等时端点
     *   - Audio Streaming Interface (class=0x01, subClass=0x02)：
     *     AltSetting 0：零带宽（无端点或端点数为 0）
     *     AltSetting 1+：包含等时 IN 端点（操作模式）
     *
     * Android USB API 把每个 (bInterfaceNumber, bAlternateSetting) 组合
     * 展平为一个 UsbInterface 对象，通过 UsbDevice.getInterface(i) 枚举。
     *
     * @return 定位到的 [StreamingEndpoint]，未找到返回 null
     */
    fun findStreamingEndpoint(device: UsbDevice): StreamingEndpoint? {
        val all = findAllStreamingEndpoints(device)
        // 选 maxPacketSize 最大的（最高采样率/最大带宽）
        val best = all.maxByOrNull { it.maxPacketSize }
        streamingEndpoint = best
        return best
    }

    /**
     * 列出所有 Audio Streaming Interface 中的等时 IN 端点（所有 alt setting）。
     *
     * XVF3800 可能有多个 alternate setting：
     *   alt=1: 8kHz  6ch  (mps=96)
     *   alt=2: 16kHz 6ch  (mps=192)
     *   alt=3: 48kHz 6ch  (mps=576)
     *
     * 此方法返回全部，供诊断和自动选择最佳配置。
     */
    fun findAllStreamingEndpoints(device: UsbDevice): List<StreamingEndpoint> {
        val results = mutableListOf<StreamingEndpoint>()
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val isAudioStreaming = iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                iface.interfaceSubclass == 0x02 // AUDIOSTREAMING

            for (epIdx in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(epIdx)

                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                    ep.direction == UsbConstants.USB_DIR_IN
                ) {
                    val result = StreamingEndpoint(
                        usbInterface     = iface,
                        interfaceNumber   = iface.id,
                        alternateSetting  = iface.alternateSetting,
                        endpointAddress   = ep.address,
                        maxPacketSize     = ep.maxPacketSize,
                        interval          = ep.interval
                    )
                    results.add(result)

                    Log.i(tag, "✅ 等时 IN: If[$i] bNum=${iface.id} alt=${iface.alternateSetting}" +
                        "  class=0x%02X subClass=0x%02X audioStreaming=$isAudioStreaming"
                        .format(iface.interfaceClass, iface.interfaceSubclass))
                    Log.i(tag, "   EP addr=0x%02X  maxPacket=${ep.maxPacketSize}" +
                        "  interval=${ep.interval}"
                        .format(ep.address))
                }
            }
        }

        if (results.isEmpty()) {
            Log.e(tag, "❌ 未找到等时 IN 端点！")
        } else {
            Log.i(tag, "✅ 共找到 ${results.size} 个等时 IN 端点，" +
                "max mps=${results.maxOf { it.maxPacketSize }}")
        }
        return results
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4b. 原始描述符解析 — controlTransfer 路径
    // ═══════════════════════════════════════════════════════════════════════

    /** USB 标准请求类型：设备到主机 + 标准类型 + 设备接收者 */
    private val REQ_TYPE_GET_DESCRIPTOR = UsbConstants.USB_DIR_IN or 0x00

    /** GET_DESCRIPTOR bRequest */
    private val USB_REQ_GET_DESCRIPTOR = 0x06

    // 描述符类型常量
    private val USB_DT_CONFIGURATION  = 0x02
    private val USB_DT_INTERFACE      = 0x04
    private val USB_DT_ENDPOINT       = 0x05
    private val USB_DT_CS_INTERFACE   = 0x24  // UAC class-specific

    // UAC class-specific descriptor subtypes (within CS_INTERFACE)
    private val UAC_CS_HEADER         = 0x01
    private val UAC_CS_FEATURE_UNIT   = 0x06
    private val UAC_CS_CLOCK_SOURCE   = 0x0A  // UAC 2.0 only

    // UAC protocol codes (from standard interface descriptor bInterfaceProtocol)
    private val UAC_PROTO_UNDEFINED   = 0x00  // Audio 1.0 (no protocol code)
    private val UAC_PROTO_V2          = 0x20  // Audio 2.0

    /** UAC 2.0 Audio Streaming interface subclass */
    private val USB_SUBCLASS_AUDIOSTREAMING = 0x02

    /** bmAttributes[1:0] — Isochronous transfer type */
    private val USB_ENDPOINT_TYPE_ISOC = 0x01

    /**
     * 深度解析 XVF3800 USB 描述符，双重验证等时端点。
     *
     * **第一步** — Android API 视角（复用 [logDeviceInterfaces]）
     *   遍历所有 Interface + AlternateSetting，打印 Android 框架层看到的
     *   class / subclass / endpoint 信息。
     *
     * **第二步** — 原始描述符视角（controlTransfer）
     *   通过 USB 标准请求 GET_DESCRIPTOR 读取 Configuration Descriptor 原始字节，
     *   手动解析 Interface Descriptor (bDescriptorType=4) 和
     *   Endpoint Descriptor (bDescriptorType=5)：
     *   - 定位 bInterfaceClass=1 (Audio), bInterfaceSubClass=2 (Streaming) 的接口
     *   - 在 alt=1 下找到 bmAttributes[1:0]=01 (Isochronous) 且方向为 IN 的端点
     *   - 输出 endpoint address 和 wMaxPacketSize
     *
     * **为什么需要第二步？**
     *   UAC 2.0 的 Alternate Setting 0（零带宽）通常无 endpoint。
     *   Android UsbInterface API 可能在某些设备/ROM 上仅暴露 alt=0，
     *   导致 [findStreamingEndpoint] 找不到端点。
     *   原始描述符解析不受框架层限制，可确认真实的硬件配置。
     *
     * @param device XVF3800 [UsbDevice]
     * @param connection 已打开的 [UsbDeviceConnection]
     * @return 解析到的 [UsbStreamingConfig]，未找到返回 null
     */
    fun dumpUsbDescriptors(
        device: UsbDevice,
        connection: UsbDeviceConnection
    ): UsbStreamingConfig? {
        // ── 第一步：Android API 枚举 ────────────────────────────────
        logDeviceInterfaces(device)

        // ── 第二步：controlTransfer 读取原始配置描述符 ──────────────
        Log.i(tag, "========== 原始描述符解析 (controlTransfer) ==========")

        // 2a. 先读 9 字节头，获取 wTotalLength
        val header = ByteArray(9)
        val headerLen = connection.controlTransfer(
            REQ_TYPE_GET_DESCRIPTOR,
            USB_REQ_GET_DESCRIPTOR,
            (USB_DT_CONFIGURATION shl 8) or 0,  // wValue: descriptor type=2, index=0
            0,                                    // wIndex
            header,
            9,
            1000                                  // timeout 1s
        )

        if (headerLen < 9) {
            Log.e(tag, "❌ controlTransfer 读取描述符头仅返回 $headerLen 字节（预期 9）")
            return null
        }

        val wTotalLength = ((header[3].toInt() and 0xFF) shl 8) or
                           (header[2].toInt() and 0xFF)
        Log.i(tag, "配置描述符 wTotalLength = $wTotalLength bytes")

        // 2b. 读取完整配置描述符（Configuration + Interface + Endpoint + ...）
        val raw = ByteArray(wTotalLength)
        val rawLen = connection.controlTransfer(
            REQ_TYPE_GET_DESCRIPTOR,
            USB_REQ_GET_DESCRIPTOR,
            (USB_DT_CONFIGURATION shl 8) or 0,
            0,
            raw,
            wTotalLength,
            1000
        )

        if (rawLen < wTotalLength) {
            Log.w(tag, "⚠ 完整描述符读取 $rawLen / $wTotalLength bytes，继续解析已获取数据")
        }
        val parseLen = minOf(rawLen, wTotalLength)

        // ── 2c. 手动解析描述符链表 ─────────────────────────────────
        // 描述符格式（前 2 字节固定）：
        //   [0] bLength       — 描述符总长度（字节）
        //   [1] bDescriptorType — 描述符类型
        // 后面字节依类型而定

        // 跳过头 9 字节的 Configuration Descriptor
        var offset = 9

        // 当前上下文（在遍历过程中维护）
        var curInterfaceNumber = 0
        var curAlternateSetting = 0
        var curInterfaceClass = 0
        var curInterfaceSubclass = 0

        // Audio Control Interface 追踪
        var acInterfaceNum = -1
        // UAC 版本: 0x00 = Audio 1.0, 0x20 = Audio 2.0（来自 bInterfaceProtocol）
        var uacProtocol = 0
        val foundFeatureUnits = mutableListOf<FeatureUnitInfo>()
        val foundClockSources = mutableListOf<ClockSourceInfo>()

        // 目标结果（Audio Streaming 端点）
        var foundInterfaceNumber = -1
        var foundAlternateSetting = -1
        var foundEndpointAddress = -1
        var foundMaxPacketSize = 0

        Log.i(tag, "--- 原始描述符遍历 ---")

        while (offset + 2 <= parseLen) {
            val descLen = raw[offset].toInt() and 0xFF
            val descType = raw[offset + 1].toInt() and 0xFF

            // 防御：长度越界或为 0 则终止
            if (descLen < 2 || offset + descLen > parseLen) {
                Log.w(tag, "  描述符越界: offset=$offset len=$descLen type=0x%02X".format(descType))
                break
            }

            when (descType) {
                USB_DT_INTERFACE -> {
                    if (descLen >= 9) {
                        curInterfaceNumber  = raw[offset + 2].toInt() and 0xFF
                        curAlternateSetting = raw[offset + 3].toInt() and 0xFF
                        val numEndpoints    = raw[offset + 4].toInt() and 0xFF
                        curInterfaceClass   = raw[offset + 5].toInt() and 0xFF
                        curInterfaceSubclass = raw[offset + 6].toInt() and 0xFF
                        val protocol         = raw[offset + 7].toInt() and 0xFF

                        val flag = if (curInterfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                                       curInterfaceSubclass == USB_SUBCLASS_AUDIOSTREAMING)
                            " ← Audio Streaming" else ""

                        Log.i(tag, "  [IF] bNum=$curInterfaceNumber alt=$curAlternateSetting" +
                            " epCount=$numEndpoints" +
                            " class=0x%02X subClass=0x%02X protocol=0x%02X$flag"
                            .format(curInterfaceClass, curInterfaceSubclass, protocol))

                        // 追踪 Audio Control Interface (class=Audio, subclass=AUDIOCONTROL)
                        if (curInterfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                            curInterfaceSubclass == 0x01 &&
                            numEndpoints == 0) {
                            acInterfaceNum = curInterfaceNumber
                            uacProtocol = protocol
                            // 保存到类字段，供 unmuteAllFeatureUnits 使用
                            uacProtocolVersion = uacProtocol
                            acInterfaceNumber = acInterfaceNum
                            val versionLabel = when (uacProtocol) {
                                UAC_PROTO_V2 -> "2.0"
                                else -> "1.0"
                            }
                            Log.i(tag, "  → Audio Control Interface (bNum=%d, UAC %s)"
                                .format(acInterfaceNum, versionLabel))
                        }
                    }
                }

                USB_DT_ENDPOINT -> {
                    if (descLen >= 7) {
                        val epAddr      = raw[offset + 2].toInt() and 0xFF
                        val bmAttributes = raw[offset + 3].toInt() and 0xFF
                        val epType      = bmAttributes and 0x03
                        val maxPacket   = ((raw[offset + 5].toInt() and 0xFF) shl 8) or
                                          (raw[offset + 4].toInt() and 0xFF)
                        val interval    = raw[offset + 6].toInt() and 0xFF

                        val dirStr  = if ((epAddr and 0x80) != 0) "IN" else "OUT"
                        val typeStr = when (epType) {
                            0 -> "CONTROL"
                            1 -> "ISOCH"
                            2 -> "BULK"
                            3 -> "INT"
                            else -> "UNKNOWN($epType)"
                        }

                        val isTarget = curInterfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                                       curInterfaceSubclass == USB_SUBCLASS_AUDIOSTREAMING &&
                                       curAlternateSetting == 1 &&
                                       epType == USB_ENDPOINT_TYPE_ISOC &&
                                       (epAddr and 0x80) != 0
                        val flag = if (isTarget) " ← 目标等时 IN" else ""

                        Log.i(tag, "  [EP] addr=0x%02X dir=$dirStr type=$typeStr" +
                            " maxPacket=$maxPacket interval=$interval$flag"
                            .format(epAddr))

                        if (isTarget) {
                            foundInterfaceNumber  = curInterfaceNumber
                            foundAlternateSetting = curAlternateSetting
                            foundEndpointAddress  = epAddr
                            foundMaxPacketSize    = maxPacket
                        }
                    }
                }

                USB_DT_CS_INTERFACE -> {
                    if (curInterfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                        curInterfaceSubclass == 0x01 &&
                        acInterfaceNum >= 0 &&
                        descLen >= 4) {  // 至少 bLength+type+subtype=3，放宽到 4
                        val subtype = raw[offset + 2].toInt() and 0xFF

                        // UAC 2.0 Clock Source (bLength 通常=8)
                        if (subtype == UAC_CS_CLOCK_SOURCE && descLen >= 7) {
                            val clockId = raw[offset + 3].toInt() and 0xFF
                            val bmControls = raw[offset + 5].toInt() and 0xFF
                            // bit 0 = CS_SAM_FREQ_CONTROL, bit 1 = CS_CLOCK_VALID_CONTROL
                            val cs = ClockSourceInfo(acInterfaceNum, clockId,
                                (bmControls and 0x01) != 0, (bmControls and 0x02) != 0)
                            foundClockSources.add(cs)
                            Log.i(tag, "  [CS] clockId=$clockId bmControls=0x%02X " +
                                "hasFreq=${cs.hasFreqControl} hasValid=${cs.hasValidControl}"
                                .format(bmControls))
                        }

                        // Feature Unit (bLength >= 7 for UAC1, >= 10 for UAC2)
                        if (subtype == UAC_CS_FEATURE_UNIT && descLen >= 7) {
                            val unitId = raw[offset + 3].toInt() and 0xFF
                            val sourceId = raw[offset + 4].toInt() and 0xFF

                            // sourceId=0 不可能（Feature Unit 必须连接到一个源）
                            if (sourceId == 0) {
                                Log.w(tag, "  [FU?] unitId=$unitId sourceId=0 — 跳过(无效)")
                                offset += descLen
                                continue
                            }

                            val hasMute: Boolean
                            val hasVolume: Boolean

                            if (uacProtocol == UAC_PROTO_V2) {
                                // UAC 2.0: bmaControls 固定 4 字节/通道，从 offset+5 开始
                                // descLen >= 5 + 4*2 + 1 = 14
                                if (descLen >= 10) {
                                    val ctrlByte0 = raw[offset + 5].toInt() and 0xFF
                                    hasMute = (ctrlByte0 and 0x01) != 0
                                    hasVolume = (ctrlByte0 and 0x02) != 0
                                } else {
                                    hasMute = false; hasVolume = false
                                }
                            } else {
                                // UAC 1.0: 有 bControlSize(1字节) 在 offset+5
                                // bmaControls[0] 从 offset+6 开始
                                val controlSize = raw[offset + 5].toInt() and 0xFF
                                if (controlSize in 1..4 && descLen >= 7 + controlSize) {
                                    val ctrlByte0 = raw[offset + 6].toInt() and 0xFF
                                    hasMute = (ctrlByte0 and 0x01) != 0
                                    hasVolume = (ctrlByte0 and 0x02) != 0
                                } else {
                                    hasMute = false; hasVolume = false
                                }
                            }

                            val fu = FeatureUnitInfo(acInterfaceNum, unitId, hasMute, hasVolume)
                            foundFeatureUnits.add(fu)
                            val ver = if (uacProtocol == UAC_PROTO_V2) "2.0" else "1.0"
                            Log.i(tag, "  [FU] unitId=$unitId sourceId=$sourceId" +
                                " hasMute=$hasMute hasVolume=$hasVolume" +
                                " (UAC $ver, AC bNum=$acInterfaceNum)")
                        }
                    }
                }

                else -> {
                    // 跳过不需要的（String, etc.）
                }
            }

            offset += descLen
        }

        // ── 结果汇总 ───────────────────────────────────────────────
        if (foundEndpointAddress < 0) {
            Log.e(tag, "❌ 原始描述符中未找到 Audio Streaming alt=1 等时 IN 端点")
            return null
        }

        // ── 保存 Feature Unit 发现 ──────────────────────────────────
        featureUnits = foundFeatureUnits.toList()
        clockSources = foundClockSources.toList()
        if (featureUnits.isNotEmpty()) {
            Log.i(tag, "✅ 发现 ${featureUnits.size} 个 Feature Unit(s):")
            featureUnits.forEach { fu ->
                Log.i(tag, "   unitId=${fu.unitId} acIf=${fu.acInterfaceNum}" +
                    " hasMute=${fu.hasMute} hasVolume=${fu.hasVolume}")
            }
        } else {
            Log.i(tag, "⚠ 未发现 Feature Unit（AC Interface bNum=$acInterfaceNum）")
        }

        val config = UsbStreamingConfig(
            interfaceId      = foundInterfaceNumber,
            alternateSetting = foundAlternateSetting,
            endpointAddress  = foundEndpointAddress,
            maxPacketSize    = foundMaxPacketSize
        )
        parsedConfig = config

        Log.i(tag, "✅ 原始描述符解析完成:")
        Log.i(tag, "   bInterfaceNumber = ${config.interfaceId}")
        Log.i(tag, "   bAlternateSetting = ${config.alternateSetting}")
        Log.i(tag, "   bEndpointAddress = 0x%02X".format(config.endpointAddress))
        Log.i(tag, "   wMaxPacketSize   = ${config.maxPacketSize}")

        return config
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 5. 音频流接口占位（Phase 3 实现）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 返回连续音频数据流。
     *
     * 双路入口设计：
     *   - **Path A（复用外部连接）**：同实例内已有连接时直接使用，selfConnected=false
     *   - **Path B（自动连接）**：冷启动或新实例，在 callbackFlow 内完成全流程连接，
     *     selfConnected=true，awaitClose 中释放自建资源
     *
     * 通过 JNI 双 URB 乒乓缓冲实现连续等时传输：
     *   1. nativeStartStreaming 创建 pthread，提交两个 URB 后进入乒乓循环
     *   2. C 层提取 6ch → 4ch PCM，累计到 1024 帧后通过 [NativePcmCallback] 回调
     *   3. Kotlin 层 Short→Float 归一化，emit [AudioData]
     *   4. 协程取消时自动调用 nativeStopStreaming 释放资源
     */
    override fun audioStream(): Flow<AudioData> = callbackFlow {
        // ── 双路入口：Path A（复用外部连接） / Path B（自动连接）──────────
        var selfConnected = false

        val conn: UsbDeviceConnection
        val ep: StreamingEndpoint
        val device: UsbDevice

        if (usbConnection != null && streamingEndpoint != null && usbDevice != null) {
            // Path A: 复用外部连接（同实例内诊断页面预连接后直接 streaming）
            conn = usbConnection!!
            ep = streamingEndpoint!!
            device = usbDevice!!
            Log.i(tag, "audioStream: 复用外部连接 (Path A) — fd=${conn.fileDescriptor}")
        } else {
            // Path B: 自动连接（冷启动直接进入 XVF3800 模式 / 新实例）
            selfConnected = true
            Log.i(tag, "audioStream: 自动连接 (Path B)")

            device = findXvf3800Device()
                ?: run {
                    close(IllegalStateException("未检测到 XVF3800 设备，请确认 USB 连接"))
                    return@callbackFlow
                }

            if (!requestPermission(device)) {
                close(IllegalStateException("USB 权限被拒绝"))
                return@callbackFlow
            }

            conn = openConnection(device)
                ?: run {
                    close(IllegalStateException("无法打开 USB 设备连接"))
                    return@callbackFlow
                }

            ep = findStreamingEndpoint(device)
                ?: run {
                    close(IllegalStateException("未找到等时 IN 端点"))
                    return@callbackFlow
                }

            dumpUsbDescriptors(device, conn)

            val iface = findInterface(device, ep.interfaceNumber, ep.alternateSetting)
            if (iface != null) {
                claimInterface(conn, iface)
            } else {
                Log.w(tag, "未找到 streaming interface bNum=${ep.interfaceNumber} alt=${ep.alternateSetting}")
            }
        }

        val fd = conn.fileDescriptor
        val numPackets = 8
        val sampleRate = AudioConfig.SAMPLE_RATE_HW

        if (isStreaming) {
            if (selfConnected) {
                try { conn.close() } catch (_: Exception) {}
                usbConnection = null
            }
            close(IllegalStateException("streaming 已在进行中"))
            return@callbackFlow
        }

        // ── 确保 app fd 持有 USBFS interface claim ──────────────────────
        val targetIface = findInterface(device, ep.interfaceNumber, ep.alternateSetting)
        if (targetIface != null) {
            if (!ensureAppFdClaim(conn, targetIface)) {
                Log.w(tag, "app fd 未持有 claim，streaming 可能在运行中意外中断")
            }
        } else {
            Log.w(tag, "未找到 streaming interface，跳过 ensureAppFdClaim")
        }

        // ── 验证用：保存前 100 帧到 raw PCM 文件（仅 debugSavePcm 时）───
        var saveRemaining = if (debugSavePcm) 100 else 0
        var fos: FileOutputStream? = null
        if (debugSavePcm) {
            try {
                val dir = File("/sdcard/Download")
                if (!dir.exists()) dir.mkdirs()
                val saveFile = File(dir, "usb_raw_4ch.pcm")
                fos = FileOutputStream(saveFile)
                Log.i(tag, "PCM save: writing first $saveRemaining frames to ${saveFile.absolutePath}")
            } catch (e: Exception) {
                Log.w(tag, "Cannot open PCM save file: ${e.message}")
            }
        }

        // ── 流错误追踪（native 线程通过 onStreamingError 回调写入）───────
        var streamingError: Throwable? = null

        val callback = object : NativePcmCallback() {
            override fun onPcmData(
                ch0: ShortArray, ch1: ShortArray, ch2: ShortArray,
                ch3: ShortArray, frameCount: Int
            ) {
                // 若已有 fatal error，不再处理后续数据
                if (streamingError != null) return

                // Short→Float 归一化（与 HardwareAudioSource 一致）
                val data = listOf(
                    FloatArray(frameCount) { ch0[it] / 32768f },
                    FloatArray(frameCount) { ch1[it] / 32768f },
                    FloatArray(frameCount) { ch2[it] / 32768f },
                    FloatArray(frameCount) { ch3[it] / 32768f }
                )

                // 保存到 raw PCM 文件（交织 short LE）
                val stream = fos
                if (saveRemaining > 0 && stream != null) {
                    try {
                        val bytes = ByteArray(frameCount * 4 * 2)
                        for (i in 0 until frameCount) {
                            val base = i * 8
                            bytes[base]     = (ch0[i].toInt() and 0xFF).toByte()
                            bytes[base + 1] = ((ch0[i].toInt() shr 8) and 0xFF).toByte()
                            bytes[base + 2] = (ch1[i].toInt() and 0xFF).toByte()
                            bytes[base + 3] = ((ch1[i].toInt() shr 8) and 0xFF).toByte()
                            bytes[base + 4] = (ch2[i].toInt() and 0xFF).toByte()
                            bytes[base + 5] = ((ch2[i].toInt() shr 8) and 0xFF).toByte()
                            bytes[base + 6] = (ch3[i].toInt() and 0xFF).toByte()
                            bytes[base + 7] = ((ch3[i].toInt() shr 8) and 0xFF).toByte()
                        }
                        stream.write(bytes)
                        saveRemaining--
                        if (saveRemaining == 0) {
                            stream.close()
                            fos = null
                            Log.i(tag, "PCM save complete")
                        }
                    } catch (e: Exception) {
                        Log.w(tag, "PCM save error: ${e.message}")
                        saveRemaining = 0
                        try { stream.close() } catch (_: Exception) {}
                        fos = null
                    }
                }

                trySend(AudioData(
                    sampleRate = sampleRate,
                    channels = 4,
                    data = data
                ))
            }

            override fun onStreamingError(errorCode: Int, message: String) {
                Log.e(tag, "Streaming fatal error (code=$errorCode): $message")
                streamingError = RuntimeException(
                    "USB streaming error $errorCode: $message")
            }
        }

        val rc = nativeStartStreaming(fd, ep.endpointAddress, ep.maxPacketSize, numPackets, callback)
        if (rc != 0) {
            try { fos?.close() } catch (_: Exception) {}
            if (selfConnected) {
                try { conn.close() } catch (_: Exception) {}
                usbConnection = null
            }
            close(IllegalStateException("nativeStartStreaming failed with rc=$rc"))
            return@callbackFlow
        }
        isStreaming = true

        // ── 监控协程：每秒检查 streaming 是否因错误退出 ──────────────────
        val monitorJob = launch {
            while (isActive) {
                delay(1000)
                val err = streamingError
                if (err != null) {
                    Log.e(tag, "Closing flow due to streaming error: ${err.message}")
                    close(err)
                    return@launch
                }
            }
        }

        awaitClose {
            monitorJob.cancel()
            try { fos?.close() } catch (_: Exception) {}
            nativeStopStreaming()
            isStreaming = false
            if (selfConnected) {
                usbConnection?.close()
                usbConnection = null
                usbDevice = null
                streamingEndpoint = null
                Log.i(tag, "Stream closed, self-connected resources released")
            } else {
                Log.i(tag, "Stream closed, external connection preserved")
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Phase 6 诊断 — 启动连续等时传输，收集指定帧数的 PCM RMS 数据。
     *
     * 内部通过 [Channel] 桥接 native 回调线程到协程，
     * 每收到一帧就通过 [onStatus] 回调，采集满 [maxFrames] 帧或 5 秒超时后停止。
     *
     * @param onStatus 每帧/PCM 状态回调，在 [Dispatchers.IO] 线程直接调用。
     *   **契约：必须为轻量非阻塞操作**（仅内存写入/state 更新），
     *   不可做 I/O、网络请求、或任何耗时操作。阻塞会延迟 channel 消费和 URB 处理。
     * @return 诊断汇总（帧数、RMS 范围、是否有非零数据）
     */
    suspend fun runStreamingDiagnosis(
        fd: Int, endpointAddress: Int, maxPacketSize: Int,
        numPackets: Int = 8, maxFrames: Int = 30,
        onStatus: (String) -> Unit,
        trace: (String) -> Unit = {}
    ): String {
        trace("PHASE6_ENTER libLoaded=$libraryLoaded streaming=$isStreaming")
        if (!libraryLoaded) return "FAIL: JNI library not loaded"
        if (isStreaming)   return "FAIL: streaming already active"

        trace("PHASE6_WITHCONTEXT_CALL")
        return withContext(Dispatchers.IO) {
            trace("PHASE6_CTX_ENTER thread=${Thread.currentThread().name}")
            val channel = Channel<String>(Channel.UNLIMITED)

            val collectedFrames = java.util.concurrent.atomic.AtomicInteger(0)
            var anyNonzero = false
            var streamingError: String? = null

            val callback = object : NativePcmCallback() {
                override fun onPcmData(
                    ch0: ShortArray, ch1: ShortArray, ch2: ShortArray,
                    ch3: ShortArray, frameCount: Int
                ) {
                    if (streamingError != null) return

                    val idx = collectedFrames.get()
                    if (idx >= maxFrames) return
                    collectedFrames.set(idx + 1)

                    // 计算 RMS
                    fun rms(arr: ShortArray): Double {
                        var sum = 0.0
                        for (i in 0 until frameCount) {
                            val v = arr[i].toDouble()
                            sum += v * v
                        }
                        return kotlin.math.sqrt(sum / frameCount)
                    }
                    val r0 = rms(ch0); val r1 = rms(ch1)
                    val r2 = rms(ch2); val r3 = rms(ch3)

                    if (r0 > 0.1 || r1 > 0.1 || r2 > 0.1 || r3 > 0.1) {
                        anyNonzero = true
                    }

                    val msg = "#%2d  rms: %6.1f %6.1f %6.1f %6.1f  %s"
                        .format(idx + 1, r0, r1, r2, r3,
                            if (r0 + r1 + r2 + r3 < 0.4) "(DSP warmup?)" else "OK")
                    channel.trySend(msg)
                }

                override fun onStreamingError(errorCode: Int, message: String) {
                    streamingError = "Error $errorCode: $message"
                    channel.trySend("STREAMING_ERROR: $streamingError")
                }

                override fun onNativeLog(level: Int, tag: String, message: String) {
                    val levelLabel = when (level) {
                        0 -> "D"; 1 -> "I"; 2 -> "W"; else -> "E"
                    }
                    channel.trySend("[native/$levelLabel] $message")
                }
            }

            isStreaming = true
            try {
                trace("PHASE6_NATIVE_START_CALL fd=$fd ep=0x${endpointAddress.toString(16)} mps=$maxPacketSize")
                val rc = nativeStartStreaming(fd, endpointAddress, maxPacketSize, numPackets, callback)
                trace("PHASE6_NATIVE_STARTED rc=$rc")
                if (rc != 0) {
                    val rcLabel = when (rc) {
                        -1 -> "already streaming (g_streaming_active=1)"
                        -2 -> "NewGlobalRef(callback) failed"
                        -3 -> "GetMethodID(onPcmData) failed — check NativePcmCallback method signature"
                        -4 -> "buffer/URB malloc failed"
                        -5 -> "pthread_create failed"
                        else -> "unknown error"
                    }
                    return@withContext "FAIL: nativeStartStreaming returned $rc ($rcLabel)"
                }

                var framesReceived = 0
                val startTimeMs = System.currentTimeMillis()
                val maxDurationMs = 6000L  // 绝对超时：6 秒，防止全零数据时无限循环
                trace("PHASE6_LOOP_ENTER maxFrames=$maxFrames")
                var firstMsg = true
                while (framesReceived < maxFrames
                       && (System.currentTimeMillis() - startTimeMs) < maxDurationMs) {
                    val msg = withTimeoutOrNull(2000L) { channel.receive() }
                    if (firstMsg) {
                        trace("PHASE6_FIRST_MSG after %dms: %s"
                            .format(System.currentTimeMillis() - startTimeMs,
                                if (msg != null) msg.take(80) else "TIMEOUT(null)"))
                        firstMsg = false
                    }
                    if (msg == null) {
                        trace("PHASE6_CHANNEL_TIMEOUT framesReceived=$framesReceived")
                        Log.e(tag, "TRACE: channel.receive() timed out, framesReceived=$framesReceived")
                        if (framesReceived == 0) {
                            channel.close()
                            return@withContext "FAIL: 2 秒内未收到任何 channel 消息 — 设备未产出数据"
                        }
                        break
                    }
                    when {
                        msg.startsWith("STREAMING_ERROR:") -> {
                            channel.close()
                            return@withContext "FAIL: ${msg.removePrefix("STREAMING_ERROR:")}"
                        }
                        msg.startsWith("[native/") -> {
                            onStatus(msg)
                        }
                        else -> {
                            framesReceived++
                            onStatus(msg)
                        }
                    }
                }

                val summary = if (anyNonzero) {
                    val actualFrames = collectedFrames.get()
                    "PASS: $actualFrames/$maxFrames frames, PCM data non-zero — Phase 6 streaming OK"
                } else {
                    "WARN: ${collectedFrames.get()}/$maxFrames frames, all near-zero — " +
                    "check DSP warmup / mute / sample rate. " +
                    "If problem persists, verify with Audacity: File > Import > Raw Data " +
                    "(Signed 16-bit PCM, LE, 4ch, 16kHz) from /sdcard/Download/usb_raw_4ch.pcm"
                }
                trace("PHASE6_RETURN: $summary")
                return@withContext summary

            } finally {
                trace("PHASE6_FINALLY_BEGIN")
                nativeStopStreaming()
                trace("PHASE6_NATIVE_STOPPED")
                channel.close()
                isStreaming = false
                trace("PHASE6_FINALLY_END")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 6. 资源释放
    // ═══════════════════════════════════════════════════════════════════════

    override fun release() {
        // 先停止 native streaming（如果在跑的话）
        try { nativeStopStreaming() } catch (_: Exception) {}
        isStreaming = false
        try { usbConnection?.close() } catch (_: Exception) {}
        usbConnection = null
        usbDevice = null
        streamingEndpoint = null
        parsedConfig = null
        featureUnits = emptyList()
        clockSources = emptyList()
        uacProtocolVersion = 0
        acInterfaceNumber = -1
        Log.i(tag, "USB 连接已释放")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 辅助
    // ═══════════════════════════════════════════════════════════════════════

    private fun endpointTypeLabel(type: Int): String = when (type) {
        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
        UsbConstants.USB_ENDPOINT_XFER_ISOC    -> "ISOC"
        UsbConstants.USB_ENDPOINT_XFER_BULK    -> "BULK"
        UsbConstants.USB_ENDPOINT_XFER_INT     -> "INT"
        else                                   -> "UNKNOWN($type)"
    }

    /**
     * 从 [UsbDevice] 的所有 Interface+AltSetting 组合中查找匹配的 [UsbInterface]。
     *
     * Android 的 UsbDevice.getInterface(i) 把每个 (bInterfaceNumber, bAlternateSetting)
     * 组合展平为一个 UsbInterface 对象。此方法遍历所有组合，找到与给定参数匹配的那个。
     *
     * @return 匹配的 [UsbInterface]，未找到返回 null
     */
    fun findInterface(device: UsbDevice, interfaceNumber: Int, alternateSetting: Int): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.id == interfaceNumber && iface.alternateSetting == alternateSetting) {
                return iface
            }
        }
        return null
    }

    /** 外部只读查询 */
    val device: UsbDevice? get() = usbDevice
    val connection: UsbDeviceConnection? get() = usbConnection
    val endpoint: StreamingEndpoint? get() = streamingEndpoint
    val config: UsbStreamingConfig? get() = parsedConfig
    val acIfNumber: Int get() = acInterfaceNumber

    /**
     * Phase 4b — 接管 Audio Control Interface（Android API 路径）。
     *
     * 在已通过 [claimInterface] 接管 Streaming Interface 后，
     * 调用此方法接管 AC Interface，以便发送 controlTransfer 到
     * Feature Unit（Mute/Volume 等控制）。
     *
     * @param device XVF3800 [UsbDevice]
     * @param connection 已打开的连接
     * @return 诊断字符串
     */
    fun claimAcInterface(device: UsbDevice, connection: UsbDeviceConnection): String {
        val sb = StringBuilder()
        val acNum = acInterfaceNumber

        if (acNum < 0) {
            sb.appendLine("⚠ acInterfaceNumber 未设置 — 请先运行 dumpUsbDescriptors()")
            return sb.toString()
        }

        val acIface = findInterface(device, acNum, 0)
        if (acIface == null) {
            sb.appendLine("⚠ 未找到 AC Interface (bNum=$acNum, alt=0)")
            sb.appendLine("  可能原因: Android API 未暴露该 interface")
            return sb.toString()
        }

        sb.appendLine("AC Interface: bNum=${acIface.id} alt=${acIface.alternateSetting}")
        sb.appendLine("  class=0x%02X subClass=0x%02X".format(acIface.interfaceClass, acIface.interfaceSubclass))

        val claimOk = connection.claimInterface(acIface, true)
        sb.appendLine("  claimInterface(force=true): ${if (claimOk) "OK" else "FAIL"}")
        if (!claimOk) {
            sb.appendLine("  → AC Interface claim 失败，后续 SET_CUR 可能仍然失败")
            return sb.toString()
        }

        val setOk = connection.setInterface(acIface)
        sb.appendLine("  setInterface: ${if (setOk) "OK" else "FAIL"}")
        return sb.toString()
    }
}
