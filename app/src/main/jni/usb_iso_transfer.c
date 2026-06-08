#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>
#include <android/log.h>
#include <pthread.h>
#include <signal.h>
#include <unistd.h>
#include <stdarg.h>

// 较老的 NDK 头文件可能未定义 USBDEVFS_DISCONNECT
#ifndef USBDEVFS_DISCONNECT
#define USBDEVFS_DISCONNECT  _IO('U', 22)
#endif

#define LOG_TAG "UsbAudioSource"

/**
 * JNI — 编译链验证函数
 *
 * ioctl(fd, USBDEVFS_CONNECTINFO, &ci) 读取设备连接信息，
 * 验证：① fd 在 native 层有效  ② JNI 调用通道通畅。
 *
 * JNI 函数名生成规则（必须逐字匹配 Kotlin external fun）：
 *   Java_<包名用_分隔>_<类名>_<方法名>
 *   包名: com.example.acousticcamera.data → com_example_acousticcamera_data
 *   类名: UsbAudioSource
 *   方法: nativeTestFd
 */
JNIEXPORT jstring JNICALL
Java_com_example_acousticcamera_data_UsbAudioSource_nativeTestFd(
    JNIEnv *env, jobject thiz, jint fd) {

    struct usbdevfs_connectinfo ci;
    memset(&ci, 0, sizeof(ci));

    int ret = ioctl(fd, USBDEVFS_CONNECTINFO, &ci);

    char buf[128];
    if (ret == 0) {
        snprintf(buf, sizeof(buf), "fd=%d, devnum=%u, slow=%u",
                 (int)fd, ci.devnum, ci.slow);
    } else {
        snprintf(buf, sizeof(buf), "fd=%d, ioctl error ret=%d",
                 (int)fd, ret);
    }

    return (*env)->NewStringUTF(env, buf);
}

/**
 * JNI — 内核驱动 detach + interface claim + alt setting 切换（纯 ioctl 路径）
 *
 * 三步操作（必须按序执行）：
 * (a) USBDEVFS_DISCONNECT  — 将内核驱动（snd-usb-audio）从目标 interface detach
 * (b) USBDEVFS_CLAIMINTERFACE — 在用户空间 claim interface
 * (c) USBDEVFS_SETINTERFACE   — 切换到操作 alternate setting（启用等时端点）
 *
 * ⚠ 平台差异：
 *   桌面 Linux：三步均可工作，此函数可独立完成接口接管。
 *   Android：USBDEVFS_DISCONNECT 通常返回 ENOTTY（内核不支持该 ioctl），
 *            CLAIMINTERFACE 随后返回 EBUSY（内核驱动仍持有 interface）。
 *            **在 Android 上，接口接管必须走 Android API 的
 *            UsbDeviceConnection.claimInterface(force=true)**，
 *            它以 system_server 权限完成内核驱动 detach。
 *            本函数在 Android 上仅用于诊断/验证，不负责实际接管。
 *
 * Kotlin 声明: external fun nativeClaimInterface(fd: Int, interfaceId: Int, altSetting: Int): String
 * 返回完整诊断字符串（每步 ioctl 的 rc + errno），可直接显示在 App UI
 */
JNIEXPORT jstring JNICALL
Java_com_example_acousticcamera_data_UsbAudioSource_nativeClaimInterface(
    JNIEnv *env, jobject thiz, jint fd, jint interfaceId, jint altSetting) {

    char buf[1024];
    int ifNum = (int)interfaceId;
    int ret;
    int pos = 0;

    // (a) USBDEVFS_DISCONNECT — detach kernel driver
    ret = ioctl(fd, USBDEVFS_DISCONNECT, &ifNum);
    if (ret < 0) {
        pos += snprintf(buf + pos, sizeof(buf) - pos,
            "USBDEVFS_DISCONNECT: rc=%d errno=%d(%s)\n",
            ret, errno, strerror(errno));

        // ENODATA(61) = no kernel driver was attached
        // ENOTTY(25)   = ioctl not supported on this kernel (common on Android)
        // ENOSYS(38)   = function not implemented
        // All three are harmless — continue to CLAIMINTERFACE
        if (errno == ENODATA || errno == ENOTTY || errno == ENOSYS) {
            pos += snprintf(buf + pos, sizeof(buf) - pos,
                "-> ok (kernel driver not active or ioctl unsupported)\n");
        } else {
            pos += snprintf(buf + pos, sizeof(buf) - pos,
                "-> FAIL: unexpected error, abort");
            return (*env)->NewStringUTF(env, buf);
        }
    } else {
        pos += snprintf(buf + pos, sizeof(buf) - pos,
            "USBDEVFS_DISCONNECT: rc=0 (kernel driver detached)\n");
    }

    // (b) USBDEVFS_CLAIMINTERFACE — 用户空间 claim
    ret = ioctl(fd, USBDEVFS_CLAIMINTERFACE, &ifNum);
    if (ret < 0) {
        pos += snprintf(buf + pos, sizeof(buf) - pos,
            "USBDEVFS_CLAIMINTERFACE: rc=%d errno=%d(%s)\n"
            "-> FAIL: device busy or already claimed",
            ret, errno, strerror(errno));
        return (*env)->NewStringUTF(env, buf);
    }
    pos += snprintf(buf + pos, sizeof(buf) - pos,
        "USBDEVFS_CLAIMINTERFACE: rc=0\n");

    // (c) USBDEVFS_SETINTERFACE — 切换到 alternate setting
    struct usbdevfs_setinterface si;
    si.interface = (unsigned int)interfaceId;
    si.altsetting = (unsigned int)altSetting;
    ret = ioctl(fd, USBDEVFS_SETINTERFACE, &si);
    if (ret < 0) {
        pos += snprintf(buf + pos, sizeof(buf) - pos,
            "USBDEVFS_SETINTERFACE: rc=%d errno=%d(%s)\n"
            "-> FAIL: cannot switch alt setting",
            ret, errno, strerror(errno));
        return (*env)->NewStringUTF(env, buf);
    }
    pos += snprintf(buf + pos, sizeof(buf) - pos,
        "USBDEVFS_SETINTERFACE: rc=0\n");

    pos += snprintf(buf + pos, sizeof(buf) - pos,
        "\nSUCCESS: interface %d claimed, alt setting %d active",
        ifNum, (int)altSetting);

    return (*env)->NewStringUTF(env, buf);
}

// 较老的 NDK 头文件可能未定义 USBDEVFS_DISCARDURB
#ifndef USBDEVFS_DISCARDURB
#define USBDEVFS_DISCARDURB  _IO('U', 14)
#endif

// 较老的 NDK 头文件可能未定义非阻塞版 REAPURB
#ifndef USBDEVFS_REAPURBNDELAY
#define USBDEVFS_REAPURBNDELAY  _IOW('U', 13, void *)
#endif

// 较老的 NDK 头文件可能未定义等时 URB 相关常量
#ifndef USBDEVFS_URB_TYPE_ISO
#define USBDEVFS_URB_TYPE_ISO  0
#endif
#ifndef USBDEVFS_URB_ISO_ASAP
#define USBDEVFS_URB_ISO_ASAP  2
#endif

/**
 * JNI — 提交单个等时 URB 并读取原始数据（Phase 5，验证模式）
 *
 * 在已通过 Android API claimInterface + setInterface 的 fd 上：
 *   (a) 分配 usbdevfs_urb + iso_packet_desc 柔性数组 + 数据 buffer
 *   (b) 填充 URB（ISO 类型、ISO_ASAP 标志）
 *   (c) ioctl(fd, USBDEVFS_SUBMITURB, urb)
 *   (d) ioctl(fd, USBDEVFS_REAPURBNDELAY, ...) 轮询等待（非阻塞，带超时）
 *   (e) 检查 urb->status 和每个 iso_frame_desc 的 status/actual_length
 *   (f) 拼接有效数据，对前 48 字节做 hex dump
 *   (g) 释放 buffer 和 urb
 *
 * 返回值：完整诊断字符串，可直接显示在 App UI。
 * 格式：多行文本，包含 SUBMITURB rc、REAPURB status、各 packet 状态、hex dump。
 *
 * Kotlin 声明:
 *   external fun nativeReadOneUrb(
 *       fd: Int, endpointAddress: Int, maxPacketSize: Int, numPackets: Int,
 *       timeoutMs: Int
 *   ): String
 *
 * ⚠ 已改用 REAPURBNDELAY 非阻塞轮询，timeoutMs 内保证返回。
 * ⚠ Phase 5 验证模式返回诊断 String；Phase 6 连续读取时改为返回 ByteArray。
 */
JNIEXPORT jstring JNICALL
Java_com_example_acousticcamera_data_UsbAudioSource_nativeReadOneUrb(
    JNIEnv *env, jobject thiz, jint fd, jint endpointAddress,
    jint maxPacketSize, jint numPackets, jint timeoutMs) {

    int ep   = (int)endpointAddress;
    int mps  = (int)maxPacketSize;
    int np   = (int)numPackets;
    int bufLen = mps * np;

    // 诊断输出缓冲区（足够容纳所有信息）
    char diag[4096];
    int pos = 0;

    // (a) 分配 URB（包含 number_of_packets 个 iso_packet_desc 的柔性数组）
    size_t urb_size = sizeof(struct usbdevfs_urb)
                    + (size_t)np * sizeof(struct usbdevfs_iso_packet_desc);
    struct usbdevfs_urb *urb = calloc(1, urb_size);
    if (!urb) {
        snprintf(diag, sizeof(diag),
            "calloc urb failed (size=%zu)", urb_size);
        return (*env)->NewStringUTF(env, diag);
    }

    // 分配数据 buffer
    unsigned char *buffer = malloc((size_t)bufLen);
    if (!buffer) {
        snprintf(diag, sizeof(diag),
            "malloc buffer failed (len=%d)", bufLen);
        free(urb);
        return (*env)->NewStringUTF(env, diag);
    }

    // (b) 填充 URB
    urb->type              = USBDEVFS_URB_TYPE_ISO;
    urb->endpoint          = ep;
    urb->flags             = USBDEVFS_URB_ISO_ASAP;
    urb->buffer            = buffer;
    urb->buffer_length     = bufLen;
    urb->number_of_packets = np;
    for (int i = 0; i < np; i++) {
        urb->iso_frame_desc[i].length = mps;
    }

    // (c) 提交 URB
    int ret = ioctl(fd, USBDEVFS_SUBMITURB, urb);
    pos += snprintf(diag + pos, sizeof(diag) - pos,
        "SUBMITURB: rc=%d", ret);
    if (ret < 0) {
        pos += snprintf(diag + pos, sizeof(diag) - pos,
            "  errno=%d(%s)\n", errno, strerror(errno));
        pos += snprintf(diag + pos, sizeof(diag) - pos,
            "=> FAIL: SUBMITURB rejected\n");
        pos += snprintf(diag + pos, sizeof(diag) - pos,
            "=> 检查：interface claim 是否成功？端点地址 0x%02X 是否匹配 alt setting？",
            ep);
        free(buffer);
        free(urb);
        return (*env)->NewStringUTF(env, diag);
    }
    pos += snprintf(diag + pos, sizeof(diag) - pos, "\n");

    // (d) 等待 URB 完成（非阻塞轮询 + 超时）
    struct usbdevfs_urb *completed = NULL;
    int timeout_limit = (int)timeoutMs;
    if (timeout_limit <= 0) timeout_limit = 4000;  // 默认 4 秒
    int elapsed_ms = 0;
    int reap_errno = 0;

    while (elapsed_ms < timeout_limit) {
        ret = ioctl(fd, USBDEVFS_REAPURBNDELAY, &completed);
        if (ret == 0) break;             // 成功收割
        reap_errno = errno;
        if (reap_errno != EAGAIN) break; // 非 EAGAIN 的错误，直接退出
        usleep(1000);                    // 1ms
        elapsed_ms++;
    }

    if (ret == 0) {
        pos += snprintf(diag + pos, sizeof(diag) - pos,
            "REAPURB ioctl rc: 0 (after %dms)\n", elapsed_ms);
    } else if (reap_errno == EAGAIN) {
        pos += snprintf(diag + pos, sizeof(diag) - pos,
            "TIMEOUT: REAPURB %dms 内未返回 — native 层已安全退出\n"
            "→ 设备可能不产出数据 / 需要播放流(OUT)激活 / 时钟无效\n",
            timeout_limit);
        goto cleanup;
    } else {
        pos += snprintf(diag + pos, sizeof(diag) - pos,
            "REAPURB ioctl rc: %d  errno=%d(%s)\n"
            "=> FAIL: REAPURB 失败\n", ret, reap_errno, strerror(reap_errno));
        goto cleanup;
    }

    // (e+f) 诊断: ptr check + raw dump + packed dump

    // diag 1: 指针校验
    if (completed->buffer != (void *)buffer) {
        pos += snprintf(diag + pos, sizeof(diag) - pos,
            "\nptr: buf=%p  completed->buffer=%p  DIFFER!!\n",
            (void *)buffer, (void *)(completed->buffer));
    } else {
        pos += snprintf(diag + pos, sizeof(diag) - pos, "\nptr: same\n");
    }

    // 确定数据源（若指针不同则优先用 completed->buffer）
    unsigned char *src = (completed->buffer != NULL && completed->buffer != (void *)buffer)
        ? (unsigned char *)(completed->buffer) : buffer;

    // 统计
    int totalActual = 0, errorCount = 0, firstActual = 0;
    for (int i = 0; i < np; i++) {
        if (completed->iso_frame_desc[i].status != 0) errorCount++;
        else {
            totalActual += completed->iso_frame_desc[i].actual_length;
            if (firstActual == 0) firstActual = completed->iso_frame_desc[i].actual_length;
        }
    }

    pos += snprintf(diag + pos, sizeof(diag) - pos,
        "urb->status=%d  total=%d  err=%d/%d  mps=%d  firstPkt=%d\n",
        completed->status, totalActual, errorCount, np, mps, firstActual);

    // 采样率估算: 6ch × 2bytes × Nsamples = firstActual
    if (firstActual > 0) {
        int rate = firstActual * 1000 / (6 * 2);
        pos += snprintf(diag + pos, sizeof(diag) - pos,
            "est rate (naive): ~%d Hz  (6ch*16bit*%d/ms=%d bytes/ms, assumes 1pkt/ms)\n",
            rate, rate / 1000, firstActual);
    }

    // iso 明细: 全部一致时合并为一行
    int allSame = 1;
    for (int i = 1; i < np; i++) {
        if (completed->iso_frame_desc[i].status   != completed->iso_frame_desc[0].status ||
            completed->iso_frame_desc[i].actual_length != completed->iso_frame_desc[0].actual_length) {
            allSame = 0;
            break;
        }
    }
    if (allSame && completed->iso_frame_desc[0].status == 0) {
        pos += snprintf(diag + pos, sizeof(diag) - pos,
            "  iso[0..%d]: status=0 actual=%d (all same)\n",
            np - 1, completed->iso_frame_desc[0].actual_length);
    } else {
        for (int i = 0; i < np; i++) {
            pos += snprintf(diag + pos, sizeof(diag) - pos,
                "  iso[%d]: status=%d actual=%d\n",
                i, completed->iso_frame_desc[i].status,
                completed->iso_frame_desc[i].actual_length);
        }
    }

    // diag 2: RAW dump — 直接从 src 读 64 字节（无任何拼接处理）
    if (firstActual > 0) {
        int n = (bufLen < 64) ? bufLen : 64;
        pos += snprintf(diag + pos, sizeof(diag) - pos,
            "\nRAW src[0..%d]:\n", n - 1);
        for (int row = 0; row < n; row += 16) {
            pos += snprintf(diag + pos, sizeof(diag) - pos, "  ");
            for (int col = 0; col < 16 && (row + col) < n; col++)
                pos += snprintf(diag + pos, sizeof(diag) - pos, "%02X ", src[row + col]);
            pos += snprintf(diag + pos, sizeof(diag) - pos, " |");
            for (int col = 0; col < 16 && (row + col) < n; col++) {
                unsigned char c = src[row + col];
                pos += snprintf(diag + pos, sizeof(diag) - pos, "%c", (c >= 32 && c < 127) ? c : '.');
            }
            pos += snprintf(diag + pos, sizeof(diag) - pos, "\n");
        }
    }

    // diag 3: packed dump（拼接有效 packet）
    if (totalActual <= 0) {
        pos += snprintf(diag + pos, sizeof(diag) - pos,
            "\n=> WARN: totalActual=0\n");
        pos += snprintf(diag + pos, sizeof(diag) - pos,
            "=> 检查 alt setting / claimInterface 的 usbInterface\n");
    } else {
        unsigned char *packed = malloc((size_t)totalActual);
        if (packed) {
            int dstOff = 0;
            for (int i = 0; i < np; i++) {
                int a = completed->iso_frame_desc[i].actual_length;
                if (a > 0 && completed->iso_frame_desc[i].status == 0) {
                    memcpy(packed + dstOff, src + i * mps, a);
                    dstOff += a;
                }
            }
            int dumpLen = (totalActual < 48) ? totalActual : 48;
            pos += snprintf(diag + pos, sizeof(diag) - pos,
                "\npacked[0..%d] (%d total):\n", dumpLen - 1, totalActual);
            for (int row = 0; row < dumpLen; row += 16) {
                pos += snprintf(diag + pos, sizeof(diag) - pos, "  ");
                for (int col = 0; col < 16 && (row + col) < dumpLen; col++)
                    pos += snprintf(diag + pos, sizeof(diag) - pos, "%02X ", packed[row + col]);
                pos += snprintf(diag + pos, sizeof(diag) - pos, "\n");
            }
            free(packed);
        }
    }

cleanup:
    // 清理已提交的 URB（超时/错误路径：DISCARDURB → REAPURBNDELAY 回收）
    if (ret != 0) {
        ioctl(fd, USBDEVFS_DISCARDURB, &urb);
        struct usbdevfs_urb *discarded = NULL;
        ioctl(fd, USBDEVFS_REAPURBNDELAY, &discarded);
    }
    free(buffer);
    free(urb);
    return (*env)->NewStringUTF(env, diag);
}

/**
 * JNI — 原生 USB 控制传输（绕过 Android 框架 EPERM 限制）
 *
 * Android 的 UsbDeviceConnection.controlTransfer() 在部分设备上会拦截
 * Host→Device 方向的 class-specific 请求（SET_CUR 等），返回 EPERM。
 *
 * 本函数直接调用 ioctl(fd, USBDEVFS_CONTROL, &ctrl)，
 * 在内核 USBFS 层执行控制传输，不受 Android 框架权限检查影响。
 *
 * Kotlin 声明:
 *   external fun nativeSendControl(
 *       fd: Int, bmRequestType: Int, bRequest: Int,
 *       wValue: Int, wIndex: Int, data: ByteArray, timeout: Int
 *   ): Int
 *
 * @return 对于 OUT 传输: 成功时返回写入字节数，失败返回 -errno
 *         对于 IN  传输: 成功时返回读取字节数（数据写入 data 数组），失败返回 -errno
 */
JNIEXPORT jint JNICALL
Java_com_example_acousticcamera_data_UsbAudioSource_nativeSendControl(
    JNIEnv *env, jobject thiz, jint fd,
    jint bmRequestType, jint bRequest, jint wValue, jint wIndex,
    jbyteArray data, jint timeout) {

    jboolean isCopy;
    jbyte *buf = (*env)->GetByteArrayElements(env, data, &isCopy);
    jsize bufLen = (*env)->GetArrayLength(env, data);

    struct usbdevfs_ctrltransfer ctrl;
    ctrl.bRequestType = (__u8)bmRequestType;
    ctrl.bRequest     = (__u8)bRequest;
    ctrl.wValue       = (__u16)wValue;
    ctrl.wIndex       = (__u16)wIndex;
    ctrl.wLength      = (__u16)bufLen;
    ctrl.data         = buf;
    ctrl.timeout      = (unsigned int)timeout;

    __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG,
        "nativeSendControl: fd=%d bmReq=0x%02X bReq=0x%02X wVal=0x%04X wIdx=0x%04X wLen=%d timeout=%d",
        (int)fd, (int)bmRequestType, (int)bRequest, (int)wValue, (int)wIndex, (int)bufLen, (int)timeout);

    int ret = ioctl(fd, USBDEVFS_CONTROL, &ctrl);

    __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG,
        "nativeSendControl: ioctl ret=%d errno=%d", ret, (ret < 0) ? errno : 0);

    // 释放 byte array（JNI_ABORT 表示不写回 — 对于 OUT 传输）
    // 对于 IN 传输，使用 0 将内核写回的数据同步到 Java 数组
    jint releaseMode = (bmRequestType & 0x80) ? 0 : JNI_ABORT;
    (*env)->ReleaseByteArrayElements(env, data, buf, releaseMode);

    return ret;
}

// ═══════════════════════════════════════════════════════════════════════════
// Phase 6: 连续等时传输 + PCM 提取
// ═══════════════════════════════════════════════════════════════════════════

#define STREAM_CHUNK_SIZE  1024
#define STREAM_OUT_CH       4
#define STREAM_USB_CH       6
#define STREAM_MIC_OFFSET   2   // 从 Channel 2 开始提取 (ch0=Conference, ch1=ASR)

// ── 全局状态（仅用于 streaming，独立于 Phase 5 诊断函数）─────────────────

static JavaVM       *g_stream_jvm        = NULL;
static jobject       g_stream_callback   = NULL;
static jmethodID     g_stream_onPcmData  = NULL;
static jmethodID     g_stream_onStreamingError = NULL;
static jmethodID     g_stream_onNativeLog  = NULL;
static int           g_streaming_active  = 0;
static pthread_t     g_stream_thread     = 0;

static int      g_s_fd        = -1;
static int      g_s_endpoint  = 0;
static int      g_s_mps       = 0;
static int      g_s_np        = 0;
static int      g_s_bufLen    = 0;
static size_t   g_s_urb_size  = 0;

static unsigned char           *g_s_bufA  = NULL;
static unsigned char           *g_s_bufB  = NULL;
static struct usbdevfs_urb     *g_s_urbA  = NULL;
static struct usbdevfs_urb     *g_s_urbB  = NULL;
static unsigned char           *g_sent_buf = NULL;
static struct usbdevfs_urb     *g_sent_urb = NULL;

// PCM 累积缓冲区（平面格式：4 通道 × 1024 帧）
static int16_t g_pcm_ch0[STREAM_CHUNK_SIZE];
static int16_t g_pcm_ch1[STREAM_CHUNK_SIZE];
static int16_t g_pcm_ch2[STREAM_CHUNK_SIZE];
static int16_t g_pcm_ch3[STREAM_CHUNK_SIZE];
static int     g_pcm_count = 0;
static int     g_warmup_skip_count = 0;   // DSP 预热 URB 计数器（限速用）

// ── 辅助：native → Kotlin 日志通道（附加于 __android_log_print，不替代）──

static void native_log(JNIEnv *env, int level, const char *tag, const char *fmt, ...) {
    if (!g_stream_callback || !g_stream_onNativeLog) return;

    char msg[512];
    va_list args;
    va_start(args, fmt);
    vsnprintf(msg, sizeof(msg), fmt, args);
    va_end(args);

    jstring jtag = (*env)->NewStringUTF(env, tag);
    jstring jmsg = (*env)->NewStringUTF(env, msg);
    if (!jtag || !jmsg) {
        if (jtag) (*env)->DeleteLocalRef(env, jtag);
        if (jmsg) (*env)->DeleteLocalRef(env, jmsg);
        return;
    }

    (*env)->CallVoidMethod(env, g_stream_callback, g_stream_onNativeLog,
        (jint)level, jtag, jmsg);

    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }

    (*env)->DeleteLocalRef(env, jtag);
    (*env)->DeleteLocalRef(env, jmsg);
}

// ── 辅助：将累积的 1024 帧通过 JNI 回调传给 Kotlin ────────────────────────

static void flush_pcm_buffer(JNIEnv *callbackEnv) {
    if (g_pcm_count <= 0) return;

    int n = g_pcm_count;

    jshortArray jch0 = (*callbackEnv)->NewShortArray(callbackEnv, n);
    jshortArray jch1 = (*callbackEnv)->NewShortArray(callbackEnv, n);
    jshortArray jch2 = (*callbackEnv)->NewShortArray(callbackEnv, n);
    jshortArray jch3 = (*callbackEnv)->NewShortArray(callbackEnv, n);

    if (!jch0 || !jch1 || !jch2 || !jch3) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
            "flush_pcm_buffer: NewShortArray failed (OOM?)");
        if (jch0) (*callbackEnv)->DeleteLocalRef(callbackEnv, jch0);
        if (jch1) (*callbackEnv)->DeleteLocalRef(callbackEnv, jch1);
        if (jch2) (*callbackEnv)->DeleteLocalRef(callbackEnv, jch2);
        if (jch3) (*callbackEnv)->DeleteLocalRef(callbackEnv, jch3);
        g_pcm_count = 0;
        return;
    }

    (*callbackEnv)->SetShortArrayRegion(callbackEnv, jch0, 0, n, g_pcm_ch0);
    (*callbackEnv)->SetShortArrayRegion(callbackEnv, jch1, 0, n, g_pcm_ch1);
    (*callbackEnv)->SetShortArrayRegion(callbackEnv, jch2, 0, n, g_pcm_ch2);
    (*callbackEnv)->SetShortArrayRegion(callbackEnv, jch3, 0, n, g_pcm_ch3);

    (*callbackEnv)->CallVoidMethod(callbackEnv, g_stream_callback,
        g_stream_onPcmData, jch0, jch1, jch2, jch3, n);

    if ((*callbackEnv)->ExceptionCheck(callbackEnv)) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
            "flush_pcm_buffer: JNI callback threw exception");
        (*callbackEnv)->ExceptionDescribe(callbackEnv);
        (*callbackEnv)->ExceptionClear(callbackEnv);
    }

    (*callbackEnv)->DeleteLocalRef(callbackEnv, jch0);
    (*callbackEnv)->DeleteLocalRef(callbackEnv, jch1);
    (*callbackEnv)->DeleteLocalRef(callbackEnv, jch2);
    (*callbackEnv)->DeleteLocalRef(callbackEnv, jch3);

    native_log(callbackEnv, 0, LOG_TAG, "PCM flush: %d frames sent to Kotlin", n);

    g_pcm_count = 0;
}

// ── 辅助：向 Kotlin 报告 streaming 致命错误 ────────────────────────────

static void report_streaming_error(JNIEnv *env, int errorCode, const char *message) {
    if (!g_stream_callback || !g_stream_onStreamingError) return;

    jstring jmsg = (*env)->NewStringUTF(env, message);
    if (!jmsg) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
            "report_streaming_error: NewStringUTF failed (OOM?)");
        return;
    }

    (*env)->CallVoidMethod(env, g_stream_callback,
        g_stream_onStreamingError, errorCode, jmsg);

    if ((*env)->ExceptionCheck(env)) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
            "report_streaming_error: JNI callback threw exception");
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }

    (*env)->DeleteLocalRef(env, jmsg);
}

// ── 辅助：检查 URB 所有有效 iso packet 数据是否全为零 ───────────────────

static int urb_is_all_zero(const struct usbdevfs_urb *urb, const unsigned char *src, int mps) {
    for (int i = 0; i < urb->number_of_packets; i++) {
        int alen = urb->iso_frame_desc[i].actual_length;
        int st   = urb->iso_frame_desc[i].status;
        if (alen <= 0 || st != 0) continue;
        const unsigned char *pkt = src + i * mps;
        for (int j = 0; j < alen; j++) {
            if (pkt[j] != 0) return 0;
        }
    }
    return 1;
}

// ── Streaming 线程入口 ────────────────────────────────────────────────────

static void *streaming_thread(void *arg) {
    (void)arg;

    JNIEnv *callbackEnv = NULL;
    if ((*g_stream_jvm)->AttachCurrentThread(g_stream_jvm, &callbackEnv, NULL) != JNI_OK) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
            "streaming_thread: AttachCurrentThread failed");
        return NULL;
    }

    int fd  = g_s_fd;
    int ep  = g_s_endpoint;
    int mps = g_s_mps;
    int np  = g_s_np;

    // 提交 URB A 和 B
    int retA = ioctl(fd, USBDEVFS_SUBMITURB, g_s_urbA);
    int retB = ioctl(fd, USBDEVFS_SUBMITURB, g_s_urbB);
    if (retA < 0 || retB < 0) {
        char errMsg[256];
        snprintf(errMsg, sizeof(errMsg),
            "Initial SUBMITURB failed: A=%d(errno=%d) B=%d(errno=%d) — "
            "check interface claim / endpoint 0x%02X",
            retA, retA < 0 ? errno : 0, retB, retB < 0 ? errno : 0, ep);
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "%s", errMsg);
        native_log(callbackEnv, 3, LOG_TAG, "%s", errMsg);
        report_streaming_error(callbackEnv, (retA < 0) ? retA : retB, errMsg);
        if (retA >= 0) ioctl(fd, USBDEVFS_DISCARDURB, &g_s_urbA);
        if (retB >= 0) ioctl(fd, USBDEVFS_DISCARDURB, &g_s_urbB);
        (*g_stream_jvm)->DetachCurrentThread(g_stream_jvm);
        return NULL;
    }

    __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG,
        "streaming_thread: started fd=%d ep=0x%02X mps=%d np=%d", fd, ep, mps, np);
    native_log(callbackEnv, 0, LOG_TAG,
        "streaming_thread: started fd=%d ep=0x%02X mps=%d np=%d", fd, ep, mps, np);

    while (__atomic_load_n(&g_streaming_active, __ATOMIC_ACQUIRE)) {
        struct usbdevfs_urb *completed = NULL;
        int ret = -1;
        int poll_us = 1000;     // 起始 1ms
        int waited_ms = 0;
        int max_ms = 50;

        // 指数退避轮询 REAPURBNDELAY，每轮检查 g_streaming_active
        while (waited_ms < max_ms
               && __atomic_load_n(&g_streaming_active, __ATOMIC_ACQUIRE)) {
            ret = ioctl(fd, USBDEVFS_REAPURBNDELAY, &completed);
            if (ret == 0 || (ret < 0 && errno != EAGAIN)) break;
            usleep(poll_us);
            waited_ms += poll_us / 1000;
            if (poll_us < 16000) poll_us *= 2;  // 指数退避，上限 16ms
        }

        if (!__atomic_load_n(&g_streaming_active, __ATOMIC_ACQUIRE)) break;

        // 纯轮询超时（EAGAIN）：不是错误，回外循环继续等下一个 URB
        if (ret < 0 && errno == EAGAIN) continue;

        if (ret < 0) {
            char errMsg[128];
            snprintf(errMsg, sizeof(errMsg),
                "REAPURB failed: ret=%d errno=%d(%s) — interface claim may be lost",
                ret, errno, strerror(errno));
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "%s", errMsg);
            native_log(callbackEnv, 3, LOG_TAG, "%s", errMsg);
            report_streaming_error(callbackEnv, ret, errMsg);
            break;
        }

        // 确定哪个 URB 完成（防御：reap 到的必须是 A 或 B）
        int isA = (completed == g_s_urbA);
        int isB = (completed == g_s_urbB);
        if (!isA && !isB) {
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                "streaming_thread: reaped unknown URB %p (A=%p B=%p) — discarding",
                (void*)completed, (void*)g_s_urbA, (void*)g_s_urbB);
            ioctl(fd, USBDEVFS_DISCARDURB, &completed);
            if (!__atomic_load_n(&g_streaming_active, __ATOMIC_ACQUIRE)) break;
            continue;
        }
        unsigned char *src = isA ? g_s_bufA : g_s_bufB;
        struct usbdevfs_urb *urb = isA ? g_s_urbA : g_s_urbB;

        // 处理 URB 状态
        if (completed->status != 0) {
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                "streaming_thread: URB status=%d (skipping data, resubmitting)",
                completed->status);
            native_log(callbackEnv, 2, LOG_TAG,
                "URB status=%d (skip, resubmit)", completed->status);
        } else if (urb_is_all_zero(completed, src, mps)) {
            __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG,
                "DSP warmup, skipping URB");
            // 限速：每 8 个全零 URB 只推 1 条到 UI（~64ms 间隔）
            g_warmup_skip_count++;
            if (g_warmup_skip_count % 8 == 1) {
                native_log(callbackEnv, 0, LOG_TAG,
                    "DSP warmup, skipped %d URBs so far", g_warmup_skip_count);
            }
        } else {
            // 首次收到非零数据：输出预热结束日志
            if (g_warmup_skip_count > 0) {
                native_log(callbackEnv, 0, LOG_TAG,
                    "DSP warmup complete after %d zero URBs, data flowing now",
                    g_warmup_skip_count);
                g_warmup_skip_count = 0;
            }

            // 提取 PCM：遍历每个 iso packet，提取 Channel 2-5
            for (int i = 0; i < np; i++) {
                int alen = completed->iso_frame_desc[i].actual_length;
                int st   = completed->iso_frame_desc[i].status;
                if (alen <= 0 || st != 0) continue;

                unsigned char *pkt = src + i * mps;
                int numSamples = alen / (STREAM_USB_CH * (int)sizeof(int16_t));

                for (int s = 0; s < numSamples; s++) {
                    int16_t *frame = (int16_t *)(pkt + s * STREAM_USB_CH * sizeof(int16_t));
                    g_pcm_ch0[g_pcm_count] = frame[STREAM_MIC_OFFSET + 0];
                    g_pcm_ch1[g_pcm_count] = frame[STREAM_MIC_OFFSET + 1];
                    g_pcm_ch2[g_pcm_count] = frame[STREAM_MIC_OFFSET + 2];
                    g_pcm_ch3[g_pcm_count] = frame[STREAM_MIC_OFFSET + 3];
                    g_pcm_count++;

                    if (g_pcm_count >= STREAM_CHUNK_SIZE) {
                        flush_pcm_buffer(callbackEnv);
                    }
                }
            }
        }

        // 重新提交该 URB（乒乓）
        if (!__atomic_load_n(&g_streaming_active, __ATOMIC_ACQUIRE)) break;

        memset(urb, 0, g_s_urb_size);
        urb->type              = USBDEVFS_URB_TYPE_ISO;
        urb->endpoint          = ep;
        urb->flags             = USBDEVFS_URB_ISO_ASAP;
        urb->buffer            = src;
        urb->buffer_length     = g_s_bufLen;
        urb->number_of_packets = np;
        for (int i = 0; i < np; i++) {
            urb->iso_frame_desc[i].length = mps;
        }

        int subRet = ioctl(fd, USBDEVFS_SUBMITURB, urb);
        if (subRet < 0) {
            char errMsg[128];
            snprintf(errMsg, sizeof(errMsg),
                "SUBMITURB resubmit failed: ret=%d errno=%d(%s) — "
                "interface may have been reclaimed by kernel driver",
                subRet, errno, strerror(errno));
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "%s", errMsg);
            native_log(callbackEnv, 3, LOG_TAG, "%s", errMsg);
            report_streaming_error(callbackEnv, subRet, errMsg);
            break;
        }
    }

    // 清空残余 PCM（不满一帧但在停止前已累积）
    if (g_pcm_count > 0) {
        native_log(callbackEnv, 0, LOG_TAG,
            "flushing residual %d frames", g_pcm_count);
        flush_pcm_buffer(callbackEnv);
    }

    __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "streaming_thread: exiting");
    native_log(callbackEnv, 0, LOG_TAG, "streaming_thread: exiting");

    (*g_stream_jvm)->DetachCurrentThread(g_stream_jvm);
    return NULL;
}

// ── 诊断辅助：将 JNI 类的所有方法签名写入文件 ──────────────────────────
// 用于 GetMethodID 失败时定位签名不匹配的根因，无需 logcat

static void dump_class_methods_to_file(JNIEnv *env, jclass clazz,
                                        const char *className, const char *filePath) {
    FILE *f = fopen(filePath, "a");
    if (!f) return;

    fprintf(f, "\n=== Methods of %s ===\n", className);

    // 获取 java.lang.Class 和 java.lang.reflect.Method 的引用
    jclass classCls = (*env)->FindClass(env, "java/lang/Class");
    jclass methodCls = (*env)->FindClass(env, "java/lang/reflect/Method");
    if (!classCls || !methodCls) {
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
        }
        fprintf(f, "  ERROR: cannot find Class or Method class\n");
        fclose(f);
        if (classCls) (*env)->DeleteLocalRef(env, classCls);
        if (methodCls) (*env)->DeleteLocalRef(env, methodCls);
        return;
    }

    // clazz.getDeclaredMethods()
    jmethodID getDeclaredMethods = (*env)->GetMethodID(env, classCls,
        "getDeclaredMethods", "()[Ljava/lang/reflect/Method;");
    if (!getDeclaredMethods) {
        (*env)->ExceptionClear(env);
        fprintf(f, "  ERROR: GetMethodID(getDeclaredMethods) failed\n");
        fclose(f);
        (*env)->DeleteLocalRef(env, classCls);
        (*env)->DeleteLocalRef(env, methodCls);
        return;
    }

    jobjectArray methods = (jobjectArray)(*env)->CallObjectMethod(env, clazz, getDeclaredMethods);
    if (!methods) {
        fprintf(f, "  ERROR: getDeclaredMethods() returned null\n");
        fclose(f);
        (*env)->DeleteLocalRef(env, classCls);
        (*env)->DeleteLocalRef(env, methodCls);
        return;
    }

    jmethodID methodToString = (*env)->GetMethodID(env, methodCls,
        "toString", "()Ljava/lang/String;");
    if (!methodToString) {
        (*env)->ExceptionClear(env);
        // 回退: 使用 getName
    }

    jsize count = (*env)->GetArrayLength(env, methods);
    fprintf(f, "  %d declared methods:\n", (int)count);

    for (jsize i = 0; i < count; i++) {
        jobject method = (*env)->GetObjectArrayElement(env, methods, i);
        if (!method) continue;

        jstring desc = NULL;
        if (methodToString) {
            desc = (jstring)(*env)->CallObjectMethod(env, method, methodToString);
        }
        if (desc) {
            const char *cstr = (*env)->GetStringUTFChars(env, desc, NULL);
            fprintf(f, "    %s\n", cstr ? cstr : "(null)");
            if (cstr) (*env)->ReleaseStringUTFChars(env, desc, cstr);
        } else {
            fprintf(f, "    (no desc)\n");
        }

        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
        }
        (*env)->DeleteLocalRef(env, method);
        if (desc) (*env)->DeleteLocalRef(env, desc);
    }

    fprintf(f, "\n");
    fclose(f);
    (*env)->DeleteLocalRef(env, methods);
    (*env)->DeleteLocalRef(env, classCls);
    (*env)->DeleteLocalRef(env, methodCls);
}

// ── JNI: nativeStartStreaming ─────────────────────────────────────────────

JNIEXPORT jint JNICALL
Java_com_example_acousticcamera_data_UsbAudioSource_nativeStartStreaming(
    JNIEnv *env, jobject thiz, jint fd, jint endpointAddress,
    jint maxPacketSize, jint numPackets, jobject callback) {

    // 清除任何可能从之前的 JNI 调用中残留的挂起异常
    (*env)->ExceptionClear(env);

    // 不允许重复启动
    if (__atomic_load_n(&g_streaming_active, __ATOMIC_ACQUIRE)) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
            "nativeStartStreaming: already streaming");
        native_log(env, 2, LOG_TAG, "nativeStartStreaming: already streaming");
        return -1;
    }

    // ── 保存参数 ──────────────────────────────────────────────────────
    g_s_fd       = (int)fd;
    g_s_endpoint = (int)endpointAddress;
    g_s_mps      = (int)maxPacketSize;
    g_s_np       = (int)numPackets;
    g_s_bufLen   = g_s_mps * g_s_np;

    g_s_urb_size = sizeof(struct usbdevfs_urb)
                 + (size_t)g_s_np * sizeof(struct usbdevfs_iso_packet_desc);

    // ── 保存 JavaVM 指针 ───────────────────────────────────────────────
    (*env)->GetJavaVM(env, &g_stream_jvm);

    // ── 保存回调 GlobalRef + MethodID ──────────────────────────────────
    g_stream_callback = (*env)->NewGlobalRef(env, callback);
    if (!g_stream_callback) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
            "nativeStartStreaming: NewGlobalRef failed");
        native_log(env, 3, LOG_TAG, "nativeStartStreaming: NewGlobalRef failed");
        return -2;
    }

    jclass callbackClass = (*env)->GetObjectClass(env, callback);
    if (!callbackClass) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
            "nativeStartStreaming: GetObjectClass returned NULL");
        (*env)->DeleteGlobalRef(env, g_stream_callback);
        g_stream_callback = NULL;
        return -3;
    }

    // 直接在 callbackClass 上查找 method ID。
    // JNI GetMethodID 会沿继承链向上搜索，无需手动回溯到父类。
    // 不绕道 GetSuperclass 的原因：
    //   R8/ProGuard（release 构建）可能将 abstract 父类内联到匿名子类，
    //   导致 GetSuperclass 返回 java.lang.Object 而非 NativePcmCallback。
    // onPcmData 参数全是 short[] + int，无泛型类型擦除，
    //   编译器不会生成 synthetic bridge 方法，直接在子类查找是安全的。

    g_stream_onPcmData = (*env)->GetMethodID(env, callbackClass,
        "onPcmData", "([S[S[S[SI)V");
    if (!g_stream_onPcmData) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
            "nativeStartStreaming: GetMethodID onPcmData failed — dumping methods to file");
        FILE *f = fopen("/sdcard/Download/jni_diag.txt", "a");
        if (f) {
            fprintf(f, "# GetMethodID(onPcmData, ([S[S[S[SI)V) FAILED\n");
            fprintf(f, "# expected signature (JNI): ([S[S[S[SI)V\n");
            fprintf(f, "# expected signature (Java): (short[] ch0, short[] ch1, short[] ch2, short[] ch3, int frameCount) -> void\n");
            if ((*env)->ExceptionCheck(env)) {
                fprintf(f, "# Pending exception:\n");
                fprintf(f, "# (check logcat for ExceptionDescribe output)\n");
            }
            fclose(f);
        }
        dump_class_methods_to_file(env, callbackClass,
            "callback (anonymous subclass)", "/sdcard/Download/jni_diag.txt");
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, callbackClass);
        (*env)->DeleteGlobalRef(env, g_stream_callback);
        g_stream_callback = NULL;
        return -3;
    }

    g_stream_onStreamingError = (*env)->GetMethodID(env, callbackClass,
        "onStreamingError", "(ILjava/lang/String;)V");
    if (!g_stream_onStreamingError) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
            "nativeStartStreaming: GetMethodID for onStreamingError failed");
        (*env)->ExceptionClear(env);
    }

    g_stream_onNativeLog = (*env)->GetMethodID(env, callbackClass,
        "onNativeLog", "(ILjava/lang/String;Ljava/lang/String;)V");
    if (!g_stream_onNativeLog) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
            "nativeStartStreaming: GetMethodID for onNativeLog failed");
        (*env)->ExceptionClear(env);
    }
    (*env)->DeleteLocalRef(env, callbackClass);

    // ── 一次性分配所有 buffer 和 URB ───────────────────────────────────
    g_s_bufA = malloc((size_t)g_s_bufLen);
    g_s_bufB = malloc((size_t)g_s_bufLen);
    g_s_urbA = calloc(1, g_s_urb_size);
    g_s_urbB = calloc(1, g_s_urb_size);

    if (!g_s_bufA || !g_s_bufB || !g_s_urbA || !g_s_urbB) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
            "nativeStartStreaming: allocation failed");
        free(g_s_bufA); free(g_s_bufB);
        free(g_s_urbA); free(g_s_urbB);
        g_s_bufA = g_s_bufB = NULL;
        g_s_urbA = g_s_urbB = NULL;
        native_log(env, 3, LOG_TAG,
            "nativeStartStreaming: buffer/URB allocation failed");
        (*env)->DeleteGlobalRef(env, g_stream_callback);
        g_stream_callback = NULL;
        return -4;
    }

    // 填充 URB A
    g_s_urbA->type              = USBDEVFS_URB_TYPE_ISO;
    g_s_urbA->endpoint          = g_s_endpoint;
    g_s_urbA->flags             = USBDEVFS_URB_ISO_ASAP;
    g_s_urbA->buffer            = g_s_bufA;
    g_s_urbA->buffer_length     = g_s_bufLen;
    g_s_urbA->number_of_packets = g_s_np;

    // 填充 URB B
    g_s_urbB->type              = USBDEVFS_URB_TYPE_ISO;
    g_s_urbB->endpoint          = g_s_endpoint;
    g_s_urbB->flags             = USBDEVFS_URB_ISO_ASAP;
    g_s_urbB->buffer            = g_s_bufB;
    g_s_urbB->buffer_length     = g_s_bufLen;
    g_s_urbB->number_of_packets = g_s_np;

    for (int i = 0; i < g_s_np; i++) {
        g_s_urbA->iso_frame_desc[i].length = g_s_mps;
        g_s_urbB->iso_frame_desc[i].length = g_s_mps;
    }

    // ── 重置累积计数器 ─────────────────────────────────────────────────
    g_pcm_count = 0;
    g_warmup_skip_count = 0;

    // ── 启动 streaming 线程 ────────────────────────────────────────────
    __atomic_store_n(&g_streaming_active, 1, __ATOMIC_RELEASE);

    int rc = pthread_create(&g_stream_thread, NULL, streaming_thread, NULL);
    if (rc != 0) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
            "nativeStartStreaming: pthread_create failed rc=%d errno=%d", rc, errno);
        __atomic_store_n(&g_streaming_active, 0, __ATOMIC_RELEASE);
        free(g_s_bufA); free(g_s_bufB);
        free(g_s_urbA); free(g_s_urbB);
        g_s_bufA = g_s_bufB = NULL;
        g_s_urbA = g_s_urbB = NULL;
        native_log(env, 3, LOG_TAG,
            "nativeStartStreaming: pthread_create failed rc=%d", rc);
        (*env)->DeleteGlobalRef(env, g_stream_callback);
        g_stream_callback = NULL;
        return -5;
    }

    __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG,
        "nativeStartStreaming: OK fd=%d ep=0x%02X mps=%d np=%d bufLen=%d",
        g_s_fd, g_s_endpoint, g_s_mps, g_s_np, g_s_bufLen);
    native_log(env, 0, LOG_TAG,
        "nativeStartStreaming: OK fd=%d ep=0x%02X mps=%d np=%d bufLen=%d",
        g_s_fd, g_s_endpoint, g_s_mps, g_s_np, g_s_bufLen);

    return 0;
}

// ── JNI: nativeStopStreaming ──────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_example_acousticcamera_data_UsbAudioSource_nativeStopStreaming(
    JNIEnv *env, jobject thiz) {

    if (!__atomic_load_n(&g_streaming_active, __ATOMIC_ACQUIRE)) {
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG,
            "nativeStopStreaming: not streaming");
        return;
    }

    __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "nativeStopStreaming: stopping...");
    native_log(env, 0, LOG_TAG, "nativeStopStreaming: stopping...");

    // 设置停止标志（release 语义保证 streaming thread 的 acquire 读到）
    __atomic_store_n(&g_streaming_active, 0, __ATOMIC_RELEASE);

    // 取消所有待处理 URB（唤醒可能正在 REAPURB 阻塞的 streaming 线程）
    if (g_s_fd >= 0) {
        ioctl(g_s_fd, USBDEVFS_DISCARDURB, &g_s_urbA);
        ioctl(g_s_fd, USBDEVFS_DISCARDURB, &g_s_urbB);

        // 提交独立分配的 sentinel URB：
        // racing 情况下 streaming 线程可能在 DISCARDURB 之后进入 REAPURB，
        // sentinel 确保它有数据可 reap 而不会永久阻塞。
        // 独立分配避免与 streaming 线程正在访问的 URB 竞争。
        g_sent_buf = malloc((size_t)g_s_bufLen);
        g_sent_urb = calloc(1, g_s_urb_size);
        if (g_sent_buf && g_sent_urb) {
            g_sent_urb->type              = USBDEVFS_URB_TYPE_ISO;
            g_sent_urb->endpoint          = g_s_endpoint;
            g_sent_urb->flags             = USBDEVFS_URB_ISO_ASAP;
            g_sent_urb->buffer            = g_sent_buf;
            g_sent_urb->buffer_length     = g_s_bufLen;
            g_sent_urb->number_of_packets = g_s_np;
            for (int i = 0; i < g_s_np; i++) {
                g_sent_urb->iso_frame_desc[i].length = g_s_mps;
            }
            ioctl(g_s_fd, USBDEVFS_SUBMITURB, g_sent_urb);
        } else {
            free(g_sent_buf); g_sent_buf = NULL;
            free(g_sent_urb); g_sent_urb = NULL;
        }
    }

    // 等待 streaming 线程退出
    // REAPURBNDELAY 轮询 + DISCARDURB 使线程在 ~10ms 内退出，500ms 足够
    if (g_stream_thread) {
        int thread_exited = 0;
        for (int i = 0; i < 5; i++) {
            if (pthread_kill(g_stream_thread, 0) != 0) {
                thread_exited = 1;
                break;
            }
            usleep(100000);  // 100ms
        }
        if (!thread_exited) {
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                "nativeStopStreaming: thread stuck after 500ms, detaching");
            native_log(env, 2, LOG_TAG,
                "nativeStopStreaming: thread stuck after 500ms, detaching");
            pthread_detach(g_stream_thread);
        }
        g_stream_thread = 0;
    }

    // 确保 sentinel 不再被内核使用（防止 streaming 线程已退出而 sentinel 仍在飞行）
    if (g_sent_urb && g_s_fd >= 0) {
        ioctl(g_s_fd, USBDEVFS_DISCARDURB, &g_sent_urb);
    }

    // 释放 buffer 和 URB
    free(g_s_bufA); g_s_bufA = NULL;
    free(g_s_bufB); g_s_bufB = NULL;
    free(g_s_urbA); g_s_urbA = NULL;
    free(g_s_urbB); g_s_urbB = NULL;
    free(g_sent_buf); g_sent_buf = NULL;
    free(g_sent_urb); g_sent_urb = NULL;

    __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG,
        "nativeStopStreaming: stopped");
    native_log(env, 0, LOG_TAG, "nativeStopStreaming: stopped");

    // 释放 JNI GlobalRef
    if (g_stream_callback) {
        (*env)->DeleteGlobalRef(env, g_stream_callback);
        g_stream_callback = NULL;
    }

    g_stream_onPcmData = NULL;
    g_stream_onStreamingError = NULL;
    g_stream_onNativeLog = NULL;

    g_s_fd = -1;
}
