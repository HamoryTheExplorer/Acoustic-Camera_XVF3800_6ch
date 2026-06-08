# Acoustic Camera (声学相机)

> 基于外接麦克风阵列的实时声源定位与声压热力图可视化 Android 应用。利用 DAS (Delay-and-Sum) 波束成形算法和 USB 直连 XVF3800 四麦克风阵列，将声音在空间中的分布渲染为 2D 热力图。

[![Min SDK](https://img.shields.io/badge/Min_SDK-24-green)](https://developer.android.com/about/versions)
[![Target SDK](https://img.shields.io/badge/Target_SDK-36-blue)](https://developer.android.com/about/versions)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple)](https://kotlinlang.org/)
[![AGP](https://img.shields.io/badge/AGP-8.13.1-cyan)](https://developer.android.com/build)
[![NDK](https://img.shields.io/badge/NDK-27.0.12077973-red)](https://developer.android.com/ndk)
[![License](https://img.shields.io/badge/License-Apache_2.0-orange)](LICENSE)

## ✨ 功能特性

- **实时波束成形** — 频域 DAS 算法，支持 CSM EMA 平均、对角线移除、导向矢量 LUT 加速
- **声压热力图** — 2D 热力图实时渲染声源空间分布，叠加摄像头预览画面
- **FFT 频谱分析** — 实时显示各通道频域能量分布，复用 DAS 内部 FFT 避免二次计算
- **多音频源支持** — 仿真模式（无硬件可用）、Android 内置麦克风、XVF3800 USB 四麦阵列
- **USB 直连驱动** — JNI + Linux USBFS ioctl 等时传输，绕过 Android AudioRecord 延迟限制
- **USB 诊断工具** — 逐步验证 USB 设备枚举、接口接管、等时端点、PCM 数据流

## 📸 截图 / 演示

<!-- TODO: 添加应用截图，建议放在 docs/screenshots/ 目录下 -->
<!-- | 热力图 + 摄像头叠加 | 频谱分析 | USB 诊断 | -->
<!-- |:---:|:---:|:---:| -->
<!-- | ![](docs/screenshots/heatmap.png) | ![](docs/screenshots/spectrum.png) | ![](docs/screenshots/diagnosis.png) | -->

## 🏗️ 架构概览

```
XVF3800 USB 6ch PCM (48kHz/16bit)
  → JNI ioctl 等时传输 (usb_iso_transfer.c) — 双 URB 乒乓缓冲
  → NativePcmCallback — C→Kotlin 回调 (4ch short[])
  → UsbAudioSource — short→float 归一化 + Flow<AudioData>
  → AudioRepository — 数据流管理
  → DasCalculatorTurbo — 频域 DAS 波束成形 (多线程并行)
  → MainViewModel — StateFlow 驱动 UI
  → Compose UI — HeatmapView + SpectrumView + CameraPreviewView
```

- **Kotlin 层**：MVVM 架构，Jetpack Compose UI，协程 + Flow 数据管道
- **C/Native 层**：USB 等时传输（Linux USBFS ioctl），PCM 提取（6ch→4ch），JNI 回调
- **JNI 桥接**：`NativePcmCallback` abstract class，C 端通过 `GetMethodID` 查找回调方法

## 🚀 快速开始

### 环境要求

| 工具 | 版本 |
|------|------|
| Android Studio | Ladybug (2024.2.1) 或更高 |
| JDK | 11+ |
| Gradle | 8.13.1 (wrapper 自带) |
| Android SDK | API 36 |
| NDK | 27.0.12077973 (Gradle 自动下载) |
| CMake | 3.22.1+ |

### 构建步骤

```bash
# 1. 克隆仓库
git clone https://github.com/YOUR_USERNAME/Acoustic-Camera.git
cd Acoustic-Camera

# 2. 复制环境配置模板并填写实际路径
cp local.properties.example local.properties
# 编辑 local.properties，设置 sdk.dir 指向你的 Android SDK 路径

# 3. Debug 构建（验证环境）
./gradlew assembleDebug

# 4. Release 构建（含 ProGuard 混淆 + 资源压缩）
./gradlew clean assembleRelease

# 5. 安装到设备
./gradlew installDebug
```

### 构建变体

| 变体 | 说明 |
|------|------|
| `debug` | 开发调试，无混淆，含 Compose Tooling |
| `release` | 发布版本，R8 混淆 + 资源压缩 + ProGuard |
| `benchmark` | 性能基准测试，基于 release 配置但使用 debug 签名 |

### 支持的 ABI

通过 NDK 交叉编译，仅编译真机 ABI：

- `arm64-v8a` (64 位 ARM)
- `armeabi-v7a` (32 位 ARM)

> 注意：本项目不包含 x86/x86_64 模拟器支持。如需模拟器运行，请在 `app/build.gradle.kts` 的 `ndk.abiFilters` 中添加对应 ABI。

## 📖 使用说明

### App 菜单

- **Main Screen**：主界面，选择音频源模式，显示实时热力图 + 频谱 + 摄像头叠加
- **USB Diagnosis**：XVF3800 USB 设备逐步诊断（Phase 1-6），适用于排查硬件连接问题
- **Settings**：应用设置和配置

### 音频源模式

1. **仿真模式 (Simulation)** — 无需任何硬件，使用模拟正弦波数据验证算法和 UI
2. **硬件模式 (Hardware)** — 使用手机内置麦克风（标准 2 通道），兼容模拟器
3. **XVF3800 模式** — 通过 USB-OTG 连接 XVF3800 四麦克风阵列，6 通道 PCM 输入，提取 Mic 2-5

## 🏗️ 项目结构

```
Acoustic-Camera/
├── build.gradle.kts                    # 根构建配置
├── settings.gradle.kts                 # 模块声明
├── gradle/
│   ├── libs.versions.toml              # 版本目录（统一依赖管理）
│   └── wrapper/
├── local.properties.example            # 环境配置模板（在版本控制中）
├── app/
│   ├── build.gradle.kts                # 应用构建配置 (NDK/CMake/依赖)
│   ├── proguard-rules.pro              # R8/ProGuard 混淆规则
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── jni/                        # ← C 原生代码
│       │   ├── CMakeLists.txt          # Native 构建配置
│       │   └── usb_iso_transfer.c      # JNI: USB 等时传输 + PCM 提取
│       ├── res/                        # 资源文件
│       └── java/com/example/acousticcamera/
│           ├── MainActivity.kt         # 单 Activity 入口 + Navigation
│           ├── algorithm/              # 波束成形核心算法
│           │   ├── DasCalculatorTurbo.kt  # 频域 DAS (多线程并行)
│           │   ├── FftUtils.kt            # FFT 工具
│           │   └── ComplexUtils.kt        # 复数运算
│           ├── data/                   # 数据层
│           │   ├── AudioSource.kt         # 音频源接口
│           │   ├── UsbAudioSource.kt      # USB 直连 + JNI 桥接
│           │   ├── HardwareAudioSource.kt # 内置麦克风
│           │   ├── SimulationAudioSource.kt # 仿真数据
│           │   ├── AudioRepository.kt     # 数据流管理
│           │   ├── AudioConfig.kt         # 音频参数配置
│           │   ├── AudioData.kt           # 音频帧数据类
│           │   ├── MicArrayConfig.kt      # 麦克风阵列几何
│           │   ├── GridConfig.kt          # 扫描网格配置
│           │   └── MicDeviceInfo.kt       # 设备信息枚举
│           ├── viewmodel/
│           │   └── MainViewModel.kt    # MVVM ViewModel (StateFlow)
│           └── ui/                     # Jetpack Compose UI
│               ├── MainScreen.kt       # 主界面（热力图+频谱+FPS）
│               ├── HomeScreen.kt       # 入口页
│               ├── SettingsScreen.kt   # 设置页
│               ├── UsbDiagnosisScreen.kt # USB 诊断页
│               ├── HeatmapView.kt      # 热力图渲染
│               ├── SpectrumView.kt     # 频谱图渲染
│               ├── CameraPreviewView.kt # 摄像头预览
│               ├── ColorUtils.kt       # 颜色映射工具
│               └── theme/              # Material3 主题
└── macrobenchmark/                     # 性能基准测试
```

## 🛠️ 技术栈

| 分类 | 技术 |
|------|------|
| UI | Jetpack Compose (BOM 2024.09.00) + Material3 |
| 架构 | MVVM + Repository Pattern |
| 异步 | Kotlin Coroutines 1.10.2 + Flow/StateFlow |
| 数学 | JTransforms 3.2 (FFT) |
| 原生 | NDK 27.0 + CMake 3.22.1 + JNI |
| 摄像头 | CameraX 1.4.0 |
| 混淆 | R8 + ProGuard (Release 构建) |

## 🎤 硬件说明

### XVF3800 麦克风阵列

- **芯片**：XMOS XVF3800 (USB Audio Class 1.0)
- **阵列**：4 路 PDM 数字 MEMS 麦克风，方形布局
- **物理尺寸**：边长 63.6mm，对角线 90mm
- **USB 接口**：同步等时传输，6 通道 16-bit PCM (48kHz)
  - Channel 0: Conference (会议混音)
  - Channel 1: ASR (语音识别)
  - Channel 2-5: 4 路原始麦克风
- **连接方式**：USB-OTG 直连 Android 设备

> 没有 XVF3800 硬件？可以使用仿真模式或内置麦克风模式体验 App。

## 📋 权限说明

| 权限 | 用途 |
|------|------|
| `RECORD_AUDIO` | 硬件模式录音（运行时申请） |
| `CAMERA` | 摄像头叠加显示（运行时申请） |
| `USB Host` (feature) | XVF3800 直连（声明但非必需） |

## 🤝 贡献指南

本项目目前为个人研究项目，欢迎 Issue 和 PR。

1. Fork 本仓库
2. 创建特性分支：`git checkout -b feature/amazing-feature`
3. 提交变更：`git commit -m 'feat: add amazing feature'`
4. 推送到分支：`git push origin feature/amazing-feature`
5. 发起 Pull Request

提交信息请遵循 [Conventional Commits](https://www.conventionalcommits.org/) 规范。

## 📄 License

本项目基于 [Apache License 2.0](LICENSE) 开源协议发布。

Copyright 2025 Acoustic Camera Contributors

---

*README 最后更新: 2025-06*
