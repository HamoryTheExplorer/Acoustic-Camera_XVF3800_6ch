# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

**⚠ 核心规则：本项目始终使用 Release 构建（ProGuard/R8 active）。Debug 构建仅用于快速排错。**

```bash
# Release build（标准构建，ProGuard + resource shrinking enabled）
./gradlew assembleRelease

# Clean + Release build（必须在修改 .c / CMakeLists.txt 后使用）
./gradlew clean assembleRelease

# Debug build（仅用于快速排错，不包含 R8 混淆）
./gradlew assembleDebug

# Unit tests
./gradlew test

# Instrumented tests (requires connected device)
./gradlew connectedAndroidTest

# Macro benchmarks (requires connected device)
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest

# 验证 native .so 是否编译成功（Release 构建后 - PowerShell）
Get-ChildItem -Path app/build -Recurse -Filter "libusb_iso_transfer.so" | Select-Object -First 3
```

**构建工具规则**：
1. Windows 环境必须使用 **PowerShell** 执行所有命令，禁止使用 Bash（`.\gradlew` 反斜杠语法在 Bash 中不可用）
2. 每次构建完成后必须执行 `.\gradlew --stop` 停掉 Gradle Daemon，否则残留的 `java.exe` 会占用 `classes.dex`，导致 Android Studio 构建冲突

**Config**: minSdk=24, targetSdk=36, compileSdk=36, Java 11, Kotlin 2.0.21, AGP 8.13.1, NDK 27.0.12077973, CMake 3.22.1

## ⚠ 编译验证规则（每次修改后必须执行）

**此规则为强制规则，不可跳过，不可让用户手动编译。**

每次修改代码文件后，立即执行编译验证：
- 修改了 `.c` 文件或 `CMakeLists.txt` → 执行 `./gradlew clean assembleRelease`
- 仅修改 `.kt` 文件 → 执行 `./gradlew assembleRelease`

编译结果处理：
1. BUILD SUCCESSFUL → 完成，输出确认
2. BUILD FAILED → 读取完整错误信息 → 定位文件和行号 → 修复 → 重新编译
3. 循环修复直到编译通过，最多 5 轮
4. 连续 5 轮失败 → 停止，列出所有未解决的错误，不要猜测

禁止输出"请手动编译验证"——编译验证是你的职责。

## 常见编译陷阱（历史踩坑记录）

持续积累，遇到新问题时追加到此处：

### Kotlin 层
- `suspend fun` 不能在非协程上下文中直接调用
- `withContext(Dispatchers.Main)` 嵌套会导致死锁（Android Main dispatcher 是单线程 Handler）
  - 正确模式: `scope.launch(Main) → withContext(IO) → withContext(Main)`
  - 错误模式: `scope.launch(Main) → withContext(Main) → withContext(IO) → withContext(Main)` ← 三层嵌套死锁
- 新增 `.kt` 文件必须确认 `package` 声明与目录结构一致
- `NativePcmCallback` 是 `abstract class` 不是 `interface`（JNI `GetMethodID` 无法找到 Kotlin interface 的默认方法）
- 新增 `open fun` 到 `NativePcmCallback` 时必须提供默认实现（否则所有子类都要改）

### R8 / ProGuard（始终活跃，因为本项目只用 Release 构建）
- R8 可能将 `abstract class` 内联到匿名子类中，导致 `GetSuperclass` 返回 `Object` 而非预期的父类
  - 现象: `GetMethodID` 返回 NULL，Kotlin 层收到 rc=-3
  - 修复: JNI 层直接在 `callbackClass` 上调用 `GetMethodID`，不绕道 `GetSuperclass`
  - 保险: `proguard-rules.pro` 已添加 `-keep` 规则保留 `NativePcmCallback` 及其子类

### JNI / Native 层
- 所有 JNI 函数名前缀必须是 `Java_com_example_acousticcamera_data_UsbAudioSource_`，逐字检查拼写
- JNI 方法签名中 Kotlin `Int` = `jint`，`ShortArray` = `jshortArray`，`String` = `jstring`
- C 代码中 pthread 必须调用 `AttachCurrentThread` 才能回调 Java/Kotlin，否则 SIGSEGV
- pthread 中使用的 `JNIEnv*` 必须是 `AttachCurrentThread` 返回的，不能用启动线程的 `env`
- pthread 退出前必须 `DetachCurrentThread`
- 修改 `.c` 文件后增量编译可能不触发 native 重编，必须 `./gradlew clean assembleRelease`

### CMake
- CMakeLists.txt 位置: `app/src/main/jni/CMakeLists.txt`
- 新增 `.c` 文件必须在 `CMakeLists.txt` 的 `add_library` 中添加
- `target_link_libraries` 必须包含 `log`（用于 `__android_log_print`）
- CMake 版本在 `app/build.gradle.kts` 中声明为 3.22.1
- 支持的 ABI: `arm64-v8a`, `armeabi-v7a`（在 `ndk.abiFilters` 中配置）
- 编译产物: `libusb_iso_transfer.so`（仅此一个 native 库，无预编译 .so 依赖）

### Android Binder IPC 阻塞规则

Android USB API 中以下方法本质是**同步 Binder IPC**，绝不能在 Main 线程调用：

- `UsbManager.openDevice()` — 打开设备，创建 fd
- `UsbDeviceConnection.controlTransfer()` — USB 控制传输
- `UsbDeviceConnection.claimInterface(force=true)` — 接口接管
- `AudioRecord.read()` — 录音数据读取

**关键点**：
- 即使包在 `suspend fun` 内，如果没有主动 `withContext(Dispatchers.IO)`，仍然在调用方线程执行
- `rememberCoroutineScope().launch {}` 默认在 `Dispatchers.Main`
- 正确模式: `scope.launch(Main) { withContext(IO) { /* Binder 调用 */ } }`
- `cancel()` 无法中断已在内核/Binder 层阻塞的调用（如 `ioctl REAPURB`），必须在 Kotlin 层加 `withTimeoutOrNull`
- `BroadcastReceiver.registerReceiver()` 需要 Looper，如果外层在 IO 线程则必须 `withContext(Dispatchers.Main)`

## Architecture Overview

**MVVM** with Jetpack Compose. Two Gradle modules:

- `:app` — Main application
- `:macrobenchmark` — Performance benchmarking (separate `benchmark` build variant)

### Data Flow

```
USB XVF3800 (6ch PCM)
  → JNI/ioctl isochronous transfer (usb_iso_transfer.c)
  → NativePcmCallback (4ch short[])
  → UsbAudioSource (short→float normalization)
  → AudioRepository (Flow<AudioData>)
  → MainViewModel (coroutines + StateFlow)
  → Compose UI
```

### Key Packages (`com.example.acousticcamera`)

| Package | Responsibility |
|---------|---------------|
| `algorithm/` | DAS beamforming (`DasCalculatorTurbo`), FFT (`FftUtils`), complex math (`ComplexUtils`) |
| `data/` | Audio sources (USB/Hardware/Simulation), mic array config, grid config |
| `ui/` | Compose screens, `HeatmapView`, `SpectrumView`, `UsbDiagnosisScreen` |
| `viewmodel/` | `MainViewModel` — orchestrates audio processing and exposes UI state |

### USB Audio Pipeline (JNI)

XVF3800 USB 音频设备通过 JNI + Linux USBFS ioctl 直连，绕过 Android AudioRecord。

关键文件：
- `usb_iso_transfer.c` — C 层等时传输，双 URB 乒乓缓冲，PCM 提取（6ch→4ch）
- `UsbAudioSource.kt` — Kotlin 层 USB 设备管理，权限、连接、描述符解析、streaming Flow
- `NativePcmCallback` — C→Kotlin 回调抽象类（onPcmData, onStreamingError, onNativeLog）
- `UsbDiagnosisScreen.kt` — USB 诊断界面（Phase 1-6 逐步验证）

JNI 线程模型：
- `nativeStartStreaming` 在 JVM 线程调用，内部创建 pthread 后立即返回
- streaming 循环在 pthread 中运行（REAPURB 阻塞等待）
- pthread 入口必须 `AttachCurrentThread`，退出前 `DetachCurrentThread`
- 回调使用 pthread 的 `JNIEnv*`，不能使用启动线程的 `env`

### Core Algorithm

`DasCalculatorTurbo` implements **Frequency-Domain Delay-and-Sum beamforming**:
- Input: raw audio chunks from 4-mic square array (side 63.6mm, diagonal 90mm)
- Output: 2D energy heatmap (spatial sound localization) + FFT spectrum
- Optimizations: zero-allocation hot loops, pre-computed geometry lookup tables, multi-threaded parallelism across CPU cores, high-priority audio thread

### Project Tree

```
Acoustic-Camera/
├── build.gradle.kts
├── settings.gradle.kts
├── CLAUDE.md                        # ← 本文件
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── res/
│       ├── jni/                                 # ← Native C 代码
│       │   ├── CMakeLists.txt
│       │   └── usb_iso_transfer.c               # JNI: isochronous USB transfer + PCM extraction
│       └── java/com/example/acousticcamera/
│           ├── MainActivity.kt
│           ├── algorithm/
│           │   ├── DasCalculatorTurbo.kt
│           │   ├── FftUtils.kt
│           │   └── ComplexUtils.kt
│           ├── data/
│           │   ├── AudioConfig.kt
│           │   ├── AudioData.kt
│           │   ├── AudioRepository.kt
│           │   ├── AudioSource.kt               # 接口：audioStream(): Flow<AudioData>
│           │   ├── SimulationAudioSource.kt
│           │   ├── HardwareAudioSource.kt
│           │   ├── UsbAudioSource.kt            # USB 直连音频源 + JNI 桥接
│           │   ├── GridConfig.kt
│           │   ├── MicArrayConfig.kt
│           │   └── MicDeviceInfo.kt
│           ├── ui/
│           │   ├── HomeScreen.kt
│           │   ├── MainScreen.kt
│           │   ├── SettingsScreen.kt
│           │   ├── UsbDiagnosisScreen.kt        # USB 诊断页面（Phase 1-6）
│           │   ├── HeatmapView.kt
│           │   ├── SpectrumView.kt
│           │   ├── CameraPreviewView.kt
│           │   ├── ColorUtils.kt
│           │   └── theme/
│           └── viewmodel/
│               └── MainViewModel.kt
└── macrobenchmark/
```

### Navigation

Single-activity app. `MainActivity` hosts a `NavHost` with destinations:
- `HomeScreen` → entry point
- `MainScreen` → live beamforming visualization (heatmap + spectrum + FPS counter)
- `SettingsScreen` → app settings and configuration
- `UsbDiagnosisScreen` → USB 设备诊断（Phase 1-6 逐步验证）

## Key Dependencies

- **Jetpack Compose BOM** 2024.09.00 — UI framework (Material3)
- **Kotlin Coroutines** 1.10.2 — async, `Flow`, `StateFlow`
- **JTransforms** 3.2 — FFT implementation
- **ProfileInstaller** 1.4.1 — Baseline Profile support
- **Benchmark Macro** 1.2.0-beta01 — performance regression testing

Dependencies are declared in `gradle/libs.versions.toml` (version catalog).

## Performance Notes

The beamforming pipeline targets real-time throughput (~80K operations/frame: 2500 grid points × 4 mics × 8 frequencies). When modifying `DasCalculatorTurbo`, avoid introducing allocations inside the hot loop and preserve the geometry lookup-table approach.

## 不要做的事

- 不要修改 `AudioSource` 接口定义
- 不要删除 Phase 5 的诊断函数（nativeSendControl、diagnoseFeatureUnit、probeClockStatus、claimAcInterface、unmuteAllFeatureUnits）
- 不要在 native 层做 short→float 归一化（留给 Kotlin 层）
- 不要手动定义 `usbdevice_fs.h` 的结构体，使用 `<linux/usbdevice_fs.h>`
- 不要在 `usb_iso_transfer.c` 中硬编码 `actual_length` 的期望值
- 诊断 UI 的长时间阻塞操作必须配套：① 可随时中断的停止按钮（`job.cancel()`）② 带闪烁的进度指示（证明 Main 线程未死）③ 停止后保留最后日志（不清空）
