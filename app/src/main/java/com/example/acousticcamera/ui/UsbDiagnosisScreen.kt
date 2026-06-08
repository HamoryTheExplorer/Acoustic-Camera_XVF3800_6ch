package com.example.acousticcamera.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.acousticcamera.data.UsbAudioSource
import com.example.acousticcamera.data.crossCorrelationTest
import com.example.acousticcamera.viewmodel.MainViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * USB 直连诊断模式 — 绕过 AudioRecord/HAL，直接通过 USB 设备 fd + ioctl 通信。
 *
 * USB 直连（绕过 AudioRecord / USB Audio HAL）不需要 RECORD_AUDIO 权限。
 * 麦克风设备列表仅为辅助参考，需要 RECORD_AUDIO 才能获取，与 USB 诊断流程完全解耦。
 *
 * 功能：
 * - 音频设备参考（使用 MainViewModel，可选）
 * - RECORD_AUDIO 权限申请（仅用于音频设备列表，独立按钮）
 * - USB 设备扫描 + USB 权限申请
 * - JNI 编译链验证（ioctl USBDEVFS_CONNECTINFO）
 * - 端点定位（Android API + 原始描述符解析）
 * - 交叉验证
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsbDiagnosisScreen(
    onBackClick: () -> Unit,
    viewModel: MainViewModel = viewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val availableMics by viewModel.availableMicDevices.collectAsState()
    val selectedMicId by viewModel.selectedMicDeviceId.collectAsState()

    var isRunning by remember { mutableStateOf(false) }
    var diagnosisJob by remember { mutableStateOf<Job?>(null) }
    var currentStep by remember { mutableStateOf("") }
    var blinkOn by remember { mutableStateOf(false) }
    var testLog by remember { mutableStateOf(listOf<DiagLine>()) }
    val phase6RmsLog = remember { mutableStateListOf<String>() }

    // ─── 进入页面时自动扫描 + 手动刷新 USB 设备状态 ──────────────────
    var deviceStatus by remember { mutableStateOf("") }
    var scanBusy by remember { mutableStateOf(false) }

    fun refreshDeviceStatus() {
        scanBusy = true
        scope.launch(Dispatchers.IO) {
            val probe = UsbAudioSource(context)
            try {
                val dev = probe.findXvf3800Device()
                withContext(Dispatchers.Main) {
                    if (dev != null) {
                        val hasPerm = probe.hasUsbPermission(dev)
                        val permLabel = if (hasPerm) "✅ 已授权" else "⚠ 需要授权"
                        deviceStatus = "检测到 XVF3800 ($permLabel) — ${dev.deviceName}"
                    } else {
                        deviceStatus = "未检测到 XVF3800"
                    }
                    scanBusy = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    deviceStatus = "扫描出错: ${e.message}"
                    scanBusy = false
                }
            } finally {
                probe.release()
            }
        }
    }

    LaunchedEffect(Unit) { refreshDeviceStatus() }

    // ─── 闪烁指示器（只在诊断运行中活跃，0.5s 间隔）─────────────────
    LaunchedEffect(isRunning) {
        if (!isRunning) return@LaunchedEffect
        while (isActive) {
            blinkOn = !blinkOn
            delay(500)
        }
    }

    // ─── RECORD_AUDIO 权限（仅用于麦克风设备列表，与 USB 直连无关）─────
    val recordAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.refreshMicDevices()
    }

    val hasAudioPerm = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("USB 直连", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── A. 麦克风设备参考（可选，独立于 USB 诊断）────────────
            SectionHeader("🎤 音频设备参考")
            Text(
                "仅用于查看系统音频输入设备列表，需 RECORD_AUDIO 权限。" +
                        "USB 直连不需要此权限。",
                fontSize = 12.sp,
                color = Color.Gray,
            )

            if (!hasAudioPerm) {
                DiagWarning("⚠ RECORD_AUDIO 未授予，无法获取音频设备列表")
                androidx.compose.material3.OutlinedButton(
                    onClick = { recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("授予 RECORD_AUDIO 权限")
                }
            } else {
                // 有权限：显示设备列表
                androidx.compose.material3.OutlinedButton(
                    onClick = { viewModel.refreshMicDevices() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (availableMics.isEmpty()) "刷新设备列表" else "刷新设备列表")
                }

                if (availableMics.isNotEmpty()) {
                    Column(modifier = Modifier.selectableGroup()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = selectedMicId == null, onClick = { viewModel.setSelectedMicDevice(null) }, role = Role.RadioButton)
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selectedMicId == null, onClick = null)
                            Spacer(Modifier.width(4.dp))
                            Text("系统默认", fontSize = 14.sp)
                        }
                        availableMics.forEach { mic ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(selected = selectedMicId == mic.id, onClick = { viewModel.setSelectedMicDevice(mic.id) }, role = Role.RadioButton)
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = selectedMicId == mic.id, onClick = null)
                                Spacer(Modifier.width(4.dp))
                                Column {
                                    Text(mic.name, fontSize = 14.sp)
                                    Text(mic.typeLabel, fontSize = 11.sp, color = Color.Gray)
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            // ── B. USB 直连诊断（不需要 RECORD_AUDIO）────────────────
            SectionHeader("🔌 USB 直连诊断")
            Text(
                "绕过 AudioRecord/HAL，直接通过 USB 设备 fd + ioctl 通信",
                fontSize = 12.sp,
                color = Color.Gray,
            )

            // 显示扫描结果 + 手动刷新按钮
            if (deviceStatus.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val statusColor = when {
                        deviceStatus.contains("✅") -> Color(0xFF81C784)
                        deviceStatus.contains("⚠") -> Color(0xFFFFCC80)
                        deviceStatus.contains("未检测到") -> Color(0xFFEF5350)
                        else -> Color.Gray
                    }
                    Text(
                        text = deviceStatus,
                        fontSize = 13.sp,
                        color = statusColor,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { refreshDeviceStatus() },
                        enabled = !scanBusy && !isRunning,
                    ) {
                        Text(
                            if (scanBusy) "扫描中..." else "重新扫描",
                            fontSize = 12.sp,
                            color = Color(0xFF90CAF9),
                        )
                    }
                }
            }

            // 步骤指示器（仅运行中显示，闪烁 ●/○ 0.5s 交替）
            if (isRunning) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (blinkOn) "●" else "○",
                        fontSize = 14.sp,
                        color = Color(0xFF4CAF50),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = currentStep.ifEmpty { "运行中..." },
                        fontSize = 13.sp,
                        color = Color(0xFFB0BEC5),
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            // 按钮：开始 / 停止二态，始终可点
            androidx.compose.material3.Button(
                onClick = {
                    if (isRunning) {
                        diagnosisJob?.cancel()
                        isRunning = false
                        currentStep = "正在终止..."
                        testLog = testLog + DiagLine.Warn("用户手动停止，等待 native 层退出...")
                        phase6RmsLog.clear()
                    } else {
                        isRunning = true
                        currentStep = ""
                        testLog = emptyList()
                        phase6RmsLog.clear()
                        val errorHandler = CoroutineExceptionHandler { _, e ->
                            val errMsg = "CRASH: ${e.javaClass.simpleName}: ${e.message}"
                            Log.e("UsbDiagnosis", "诊断异常", e)
                            // 使用 Handler post 到 Main 线程确保 Compose snapshot 感知变更
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                testLog = testLog + DiagLine.Fail(errMsg)
                                phase6RmsLog.add(errMsg)
                                isRunning = false
                                currentStep = ""
                            }
                        }
                        diagnosisJob = scope.launch(errorHandler) {
                            // ─── 文件 trace + 看门狗 ───────────────────────────
                            val traceFile = File("/sdcard/Download/diagnosis_trace.txt")
                            val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
                            fun trace(msg: String) {
                                val line = "${sdf.format(Date())} [${Thread.currentThread().name}] $msg\n"
                                try { traceFile.appendText(line) } catch (_: Exception) {}
                            }
                            trace("DIAGNOSIS_START thread=${Thread.currentThread().name}")

                            // 看门狗独立于诊断生命周期：诊断结束后不 cancel，继续跑
                            // 如果 UI 真的卡死（state 写了但 recompose 没执行），
                            // 20s 后 dump 会揭示 Main 线程在做什么
                            trace("WATCHDOG_START (20s persistent)")
                            val dumpFile = File("/sdcard/Download/thread_dump.txt")
                            val watchdog = Thread({
                                try {
                                    for (beat in 1..7) {
                                        Thread.sleep(3_000)
                                        trace("WATCHDOG_HEARTBEAT $beat/7 alive")
                                    }
                                    trace("WATCHDOG_DUMP_START")
                                    val dump = StringBuilder()
                                    dump.appendLine("=== Thread Dump ${sdf.format(Date())} ===")
                                    Thread.getAllStackTraces().forEach { (t, stack) ->
                                        dump.appendLine("\n\"${t.name}\" state=${t.state} daemon=${t.isDaemon}")
                                        stack.forEach { dump.appendLine("  at $it") }
                                    }
                                    dump.appendLine("\n=== END ===")
                                    dumpFile.writeText(dump.toString())
                                    trace("WATCHDOG_DUMP_DONE (${dump.length} chars)")
                                    Log.e("Watchdog", "Thread dump → /sdcard/Download/thread_dump.txt")
                                } catch (_: InterruptedException) {
                                    trace("WATCHDOG_CANCELLED")
                                } catch (e: Exception) {
                                    trace("WATCHDOG_ERROR: ${e.message}")
                                }
                            }, "diagnosis-watchdog").apply { isDaemon = true; start() }

                            try {
                                withContext(Dispatchers.IO) {
                                    trace("ENTER_IO_DISPATCHER")
                                    runFullUsbDiagnosis(context,
                                        onLog = { line ->
                                            testLog = testLog + line
                                        },
                                        onStep = { step ->
                                            trace("STEP: $step")
                                            currentStep = step
                                        },
                                        trace = { msg -> trace(msg) },
                                        onPhase6Rms = { msg ->
                                            phase6RmsLog.add(msg)
                                        })
                                    trace("EXIT_IO_DISPATCHER")
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                trace("CANCELLATION_CAUGHT: ${e.message} — rethrowing")
                                throw e
                            } catch (e: Throwable) {
                                trace("UNCATCHABLE_THROW: ${e.javaClass.name}: ${e.message} — rethrowing")
                                // 非 Exception 的 Throwable（如 NoSuchMethodError）
                                // 已通过 finally 设置了 isRunning=false，继续抛出让 CEH 处理
                                throw e
                            } finally {
                                trace("DIAGNOSIS_CODE_DONE — setting isRunning=false")
                                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                                    isRunning = false
                                }
                                trace("STATE: isRunning set to false, waiting for recompose...")
                            }
                        }
                    }
                },
                enabled = true,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = if (isRunning)
                    androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFB71C1C)
                    )
                else
                    androidx.compose.material3.ButtonDefaults.buttonColors()
            ) {
                if (isRunning) {
                    Text("停止诊断", color = Color.White)
                } else {
                    Text("运行 USB 诊断")
                }
            }

            // ── C. 诊断结果（按 Section 折叠，默认只展开最后一段）────
            if (testLog.isNotEmpty()) {
                // 按 DiagLine.Section 拆分为段，每段可折叠
                data class LogChunk(val header: String, val lines: List<DiagLine>)
                val chunks = remember(testLog) {
                    val result = mutableListOf<LogChunk>()
                    var curHeader: String? = null
                    val curLines = mutableListOf<DiagLine>()
                    for (line in testLog) {
                        if (line is DiagLine.Section) {
                            if (curHeader != null) {
                                result.add(LogChunk(curHeader!!, curLines.toList()))
                                curLines.clear()
                            }
                            curHeader = line.text
                        } else if (curHeader != null) {
                            curLines.add(line)
                        }
                    }
                    if (curHeader != null) {
                        result.add(LogChunk(curHeader!!, curLines.toList()))
                    }
                    result.toList()
                }

                chunks.forEachIndexed { chunkIdx, chunk ->
                    val lastIdx = chunks.lastIndex
                    var expanded by remember(chunkIdx, testLog.size) {
                        mutableStateOf(chunkIdx == lastIdx)
                    }

                    // 折叠/展开 触发行
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = !expanded }
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (expanded) "▼" else "▶",
                            fontSize = 11.sp,
                            color = Color(0xFF90CAF9),
                            modifier = Modifier.width(14.dp),
                        )
                        Text(
                            text = chunk.header,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color(0xFF90CAF9),
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${chunk.lines.size} 条",
                            fontSize = 10.sp,
                            color = Color(0xFF616161),
                        )
                    }

                    // 段内容（可折叠）
                    AnimatedVisibility(
                        visible = expanded,
                        enter = expandVertically(),
                        exit = shrinkVertically(),
                    ) {
                        Column {
                            chunk.lines.forEach { line ->
                                when (line) {
                                    is DiagLine.Ok -> Text(
                                        text = line.text,
                                        fontSize = 13.sp,
                                        color = Color(0xFF81C784),
                                        fontFamily = FontFamily.Monospace,
                                    )
                                    is DiagLine.Fail -> Text(
                                        text = line.text,
                                        fontSize = 13.sp,
                                        color = Color(0xFFEF5350),
                                        fontFamily = FontFamily.Monospace,
                                    )
                                    is DiagLine.Warn -> Text(
                                        text = line.text,
                                        fontSize = 13.sp,
                                        color = Color(0xFFFFCC80),
                                        fontFamily = FontFamily.Monospace,
                                    )
                                    is DiagLine.Info -> Text(
                                        text = line.text,
                                        fontSize = 13.sp,
                                        color = Color(0xFFB0BEC5),
                                        fontFamily = FontFamily.Monospace,
                                    )
                                    is DiagLine.Section -> {} // 不渲染：已在 header 显示
                                }
                            }
                        }
                    }
                }
            } else if (!isRunning) {
                DiagInfoCard(
                    "点击「运行 USB 诊断」后，将依次执行：",
                    listOf(
                        "1. 扫描 USB 设备 → 查找 XVF3800",
                        "2. 请求 USB 设备权限（系统弹出对话框）",
                        "3. 打开 USB 连接",
                        "4. JNI 编译链验证（ioctl USBDEVFS_CONNECTINFO）",
                        "5. 端点定位（Android API）",
                        "6. 原始描述符解析（controlTransfer）",
                        "7. 交叉验证 Android API vs 原始描述符",
                        "8. 接管接口（Android API claim force=true）",
                        "9a. 接管 AC Interface",
                        "9b. Feature Unit 诊断 (Mute + Vol + SET_CUR)",
                        "9c. Clock Source 探测",
                        "9d. 首次 URB 提交（Phase 5，仅关键状态）",
                        "9e. 二次 URB 读取（hex dump 验证）",
                        "10. Phase 6 连续等时采集 (2秒, 30帧) → RMS 实时显示",
                        "11. E2E Test 2: ch0-ch1 互相关验证（通道独立性）",
                    )
                )
            }

            // ── D. Phase 6 实时 RMS 日志（深色底 + 等宽字体 + 可滚动）──
            if (phase6RmsLog.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                var copyLabel by remember { mutableStateOf("复制全部") }
                @Suppress("DEPRECATION")
                val clipboardManager = LocalClipboardManager.current

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Phase 6 关键日志 (${phase6RmsLog.size} 条)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Color(0xFF90CAF9),
                    )
                    TextButton(onClick = {
                        clipboardManager.setText(AnnotatedString(phase6RmsLog.joinToString("\n")))
                        copyLabel = "已复制!"
                    }) {
                        Text(copyLabel, fontSize = 12.sp, color = Color(0xFF90CAF9))
                    }
                }

                val rmsListState = rememberLazyListState()
                LaunchedEffect(phase6RmsLog.size) {
                    if (phase6RmsLog.isNotEmpty()) {
                        rmsListState.animateScrollToItem(phase6RmsLog.size - 1)
                    }
                }

                LazyColumn(
                    state = rmsListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 220.dp)
                        .background(Color(0xFF1A1A2E), RoundedCornerShape(6.dp))
                        .padding(6.dp)
                ) {
                    items(phase6RmsLog.size) { idx ->
                        val text = phase6RmsLog[idx]
                        val textColor = when {
                            text.contains("FAIL") || text.contains("Error") || text.contains("TIMEOUT") ->
                                Color(0xFFEF5350)
                            text.startsWith("[native/E]") ->
                                Color(0xFFEF5350)
                            text.startsWith("[native/W]") ->
                                Color(0xFFFFCC80)
                            text.startsWith("[native/I]") || text.contains("OK") ->
                                Color(0xFF81C784)
                            text.startsWith("[native/D]") ->
                                Color(0xFF616161)
                            text.contains("PASS") ->
                                Color(0xFF81C784)
                            text.contains("WARN") ->
                                Color(0xFFFFCC80)
                            else -> Color(0xFFB0BEC5)
                        }
                        Text(
                            text = "#$idx $text",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = textColor,
                            lineHeight = 14.sp,
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ─── 诊断行程模型 ────────────────────────────────────────────────────────────

sealed class DiagLine {
    abstract val text: String
    data class Section(override val text: String) : DiagLine()
    data class Ok(override val text: String) : DiagLine()
    data class Fail(override val text: String) : DiagLine()
    data class Warn(override val text: String) : DiagLine()
    data class Info(override val text: String) : DiagLine()
}

// ─── 诊断逻辑（不涉及 RECORD_AUDIO，仅 USB 操作）─────────────────────────────

private suspend fun runFullUsbDiagnosis(
    context: android.content.Context,
    onLog: (DiagLine) -> Unit,
    onStep: (String) -> Unit = {},
    trace: (String) -> Unit = {},
    onPhase6Rms: (String) -> Unit = {},
) {
    val usbSource = UsbAudioSource(context)
    try {
        onLog(DiagLine.Section("=== XVF3800 USB 诊断 (Phase 1+2+3) ==="))
        onLog(DiagLine.Info("目标: VID=0x2886 PID=0x001A"))

        // ── 步骤 1: 扫描 USB 设备 ──────────────────────────────────
        onStep("步骤1/10: 扫描 USB 设备...")
        onLog(DiagLine.Section("--- 步骤1: 扫描 USB 设备 ---"))
        val device = usbSource.findXvf3800Device()
        if (device == null) {
            onLog(DiagLine.Fail("FAIL: 未发现 XVF3800"))
            onLog(DiagLine.Info("请确认: OTG 线缆已连接 / 设备已上电"))
            return
        }
        onLog(DiagLine.Ok("OK: 发现 ${device.deviceName}"))
        onLog(DiagLine.Info("  VID=0x%04X  PID=0x%04X".format(device.vendorId, device.productId)))
        onLog(DiagLine.Info("  interfaceCount=${device.interfaceCount}"))

        // ── 步骤 2+3: USB 权限 → 打开连接（含自动重试）─────────
        // openConnection 失败时会自动清理进程内过期缓存；
        // 此处捕获失败后重新弹出权限对话框，再试一次。
        onStep("步骤2/10: 请求 USB 权限...")
        onLog(DiagLine.Section("--- 步骤2: 请求 USB 权限 ---"))
        val granted1 = withContext(Dispatchers.Main) { usbSource.requestPermission(device) }
        if (!granted1) {
            onLog(DiagLine.Fail("FAIL: 用户拒绝授权"))
            return
        }
        onLog(DiagLine.Ok("OK: 权限已授予"))

        onStep("步骤3/10: 打开 USB 连接...")
        onLog(DiagLine.Section("--- 步骤3: 打开 USB 连接 ---"))
        var conn = usbSource.openConnection(device)
        if (conn == null) {
            // 缓存已由 openConnection 自动清理，重试权限 → 再打开
            onLog(DiagLine.Warn("⚠ 首次连接失败（权限缓存可能过期），重新请求权限..."))
            onLog(DiagLine.Info("(请再次查看系统弹出对话框...)"))
            val granted2 = withContext(Dispatchers.Main) { usbSource.requestPermission(device) }
            if (!granted2) {
                onLog(DiagLine.Fail("FAIL: 用户拒绝授权（重试）"))
                return
            }
            conn = usbSource.openConnection(device)
            if (conn == null) {
                onLog(DiagLine.Fail("FAIL: openDevice() 返回 null（重试后仍失败）"))
                onLog(DiagLine.Info("可能原因:"))
                onLog(DiagLine.Info("  1. 内核 USB 音频驱动 (snd-usb-audio) 占用设备"))
                onLog(DiagLine.Info("  2. Android USB Audio HAL 已独占该接口"))
                onLog(DiagLine.Info("  3. SELinux 策略禁止直接访问 USB 设备"))
                onLog(DiagLine.Info("→ 需要在 JNI 层 ioctl(fd, USBDEVFS_DISCONNECT) 分离内核驱动"))
                return
            }
        }
        onLog(DiagLine.Ok("OK: 连接已建立"))
        onLog(DiagLine.Info("  fd=${conn.fileDescriptor}"))

        // ── 步骤 3b: JNI 编译链验证 ────────────────────────────────
        onStep("步骤4/10: JNI 编译链验证...")
        onLog(DiagLine.Section("--- 步骤3b: JNI 编译链验证 ---"))
        val jniResult = usbSource.verifyJniChain(conn.fileDescriptor)
        onLog(DiagLine.Info("  $jniResult"))
        when {
            jniResult.startsWith("JNI not built:") -> {
                onLog(DiagLine.Fail("FAIL: JNI 库未编译"))
                onLog(DiagLine.Info("→ Phase 3 编译链未建立，请添加 NDK 配置到 build.gradle.kts"))
            }
            jniResult.startsWith("JNI OK, ioctl error:") -> {
                onLog(DiagLine.Ok("OK: JNI 调用成功，native 函数已执行"))
                onLog(DiagLine.Warn("⚠ 但 ioctl USBDEVFS_CONNECTINFO 返回错误"))
                onLog(DiagLine.Info("→ Phase 3 编译链畅通！ioctl 失败是内核/设备差异，Phase 4 不受影响"))
            }
            jniResult.startsWith("fd=") && jniResult.contains("devnum=") -> {
                onLog(DiagLine.Ok("OK: JNI 调用成功，ioctl 返回有效数据"))
                if (jniResult.contains("slow=1")) {
                    onLog(DiagLine.Warn("⚠ Full-Speed (12Mbps)，带宽受限"))
                } else if (jniResult.contains("slow=0")) {
                    onLog(DiagLine.Info("  High-Speed (480Mbps)"))
                }
            }
            else -> {
                onLog(DiagLine.Fail("FAIL: JNI 返回异常: $jniResult"))
            }
        }

        // ── 步骤 4: 端点定位 (Android API) ─────────────────────────
        onStep("步骤5/10: 定位等时端点...")
        onLog(DiagLine.Section("--- 步骤4: 等时 IN 端点 (所有 alt setting) ---"))
        val allEps = usbSource.findAllStreamingEndpoints(device)
        if (allEps.isEmpty()) {
            onLog(DiagLine.Fail("FAIL: 未找到等时 IN 端点"))
            onLog(DiagLine.Info("请确认固件为六通道等时传输模式"))
            return
        }
        // 显示所有 alt setting
        onLog(DiagLine.Ok("找到 ${allEps.size} 个等时 IN 端点:"))
        for (ep in allEps) {
            onLog(DiagLine.Info(
                "  bNum=${ep.interfaceNumber} alt=${ep.alternateSetting}" +
                "  addr=0x%02X  mps=${ep.maxPacketSize}".format(ep.endpointAddress)))
        }

        // 选 maxPacketSize 最大的（最高采样率）
        val epApi = allEps.maxByOrNull { it.maxPacketSize }!!
        onLog(DiagLine.Ok("自动选择: bNum=${epApi.interfaceNumber} alt=${epApi.alternateSetting}" +
            "  mps=${epApi.maxPacketSize}  addr=0x%02X".format(epApi.endpointAddress)))

        // ── 步骤 5: 原始描述符解析 ─────────────────────────────────
        onStep("步骤6/10: 原始描述符解析 (controlTransfer)...")
        onLog(DiagLine.Section("--- 步骤5: 原始描述符解析 (controlTransfer) ---"))
        val config = usbSource.dumpUsbDescriptors(device, conn)
        if (config == null) {
            onLog(DiagLine.Fail("FAIL: 原始描述符解析失败"))
            return
        }
        onLog(DiagLine.Ok("OK: 描述符解析完成"))
        onLog(DiagLine.Info("  bInterfaceNumber = ${config.interfaceId}"))
        onLog(DiagLine.Info("  bAlternateSetting = ${config.alternateSetting}"))
        onLog(DiagLine.Info("  endpointAddress = 0x%02X".format(config.endpointAddress)))
        onLog(DiagLine.Info("  wMaxPacketSize = ${config.maxPacketSize}"))

        // ── 步骤 6: 交叉验证 ───────────────────────────────────────
        onStep("步骤7/10: 交叉验证端点...")
        onLog(DiagLine.Section("--- 步骤6: 交叉验证 API vs 原始描述符 ---"))
        val mismatches = mutableListOf<String>()
        // 原始描述符解析只找 alt=1；若 API 最佳结果不是 alt=1，则提示用户
        if (epApi.interfaceNumber  != config.interfaceId)      mismatches.add("interfaceNumber: API=${epApi.interfaceNumber}  RAW=${config.interfaceId}")
        if (epApi.alternateSetting != config.alternateSetting) mismatches.add("altSetting: API(最佳)=${epApi.alternateSetting}  RAW=${config.alternateSetting}  ← 使用 API 值!")
        if (epApi.endpointAddress  != config.endpointAddress)  mismatches.add("epAddr: API=0x%02X  RAW=0x%02X".format(epApi.endpointAddress, config.endpointAddress))
        if (epApi.maxPacketSize    != config.maxPacketSize)    mismatches.add("maxPacket: API=${epApi.maxPacketSize}  RAW=${config.maxPacketSize}  ← 使用 API 值!")

        // 使用 API 的最佳端点（而非原始描述符的 alt=1），合并到 config 中
        val effectiveConfig = if (epApi.alternateSetting != config.alternateSetting ||
                                  epApi.maxPacketSize != config.maxPacketSize) {
            onLog(DiagLine.Info("→ 后续步骤使用 API 端点: alt=${epApi.alternateSetting} mps=${epApi.maxPacketSize}"))
            UsbAudioSource.UsbStreamingConfig(
                interfaceId = epApi.interfaceNumber,
                alternateSetting = epApi.alternateSetting,
                endpointAddress = epApi.endpointAddress,
                maxPacketSize = epApi.maxPacketSize
            )
        } else null

        val finalConfig = effectiveConfig ?: config

        if (mismatches.isEmpty()) {
            onLog(DiagLine.Ok("OK: 全部一致"))
        } else {
            onLog(DiagLine.Warn("⚠ ${mismatches.size} 项不一致 → 使用 API 最佳端点"))
            mismatches.forEach { onLog(DiagLine.Info("  $it")) }
        }

        // ── 步骤 7: 接管接口 (Phase 4) ───────────────────────────────
        // claimInterface(force=true) → system_server 以 root 权限
        // 完成内核驱动 detach（sysfs unbind） + claim。
        onStep("步骤8/10: 接管 Streaming Interface (Phase 4)...")
        onLog(DiagLine.Section("--- 步骤7: 接管接口 (Phase 4) ---"))
        onLog(DiagLine.Info("  claimInterface(force=true) + setInterface(alt=${finalConfig.alternateSetting})"))

        val targetInterface = usbSource.findInterface(
            device, finalConfig.interfaceId, finalConfig.alternateSetting
        ) ?: epApi.usbInterface  // fallback: 用 Android API 找到的接口

        val claimResult = usbSource.claimInterface(conn, targetInterface)

        for (line in claimResult.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            when {
                trimmed.startsWith("SUCCESS") || trimmed.contains("OK") ->
                    onLog(DiagLine.Ok("  $trimmed"))
                trimmed.contains("FAIL") ->
                    onLog(DiagLine.Fail("  $trimmed"))
                else -> onLog(DiagLine.Info("  $trimmed"))
            }
        }

        if (claimResult.contains("SUCCESS")) {
            onLog(DiagLine.Section("=== Phase 4 (接管接口) 通过 ==="))
            onLog(DiagLine.Info("接口已被应用接管，内核驱动已分离"))
            onLog(DiagLine.Info("下一步: JNI 等时 URB 提交 (Phase 5)"))

            val epAddr = finalConfig.endpointAddress
            val epNum = epAddr and 0x0F
            onLog(DiagLine.Info("→ 端点 0x%02X: EP%d IN".format(epAddr, epNum)))

            // ── 步骤 8: 在 app fd 上完成 USBFS claim ───────────────────
            //
            // 步骤 7 的 Android API claimInterface(force=true) 以 system_server
            // 的 root 权限通过 sysfs unbind 完成内核驱动 detach。这个操作不依赖
            // USBDEVFS_DISCONNECT ioctl，所以即使内核返回 ENOTTY 也能成功。
            //
            // 但 Android API 的 claim 不一定反映到 app 自己的 fd 上。
            // Phase 5 的等时 URB 提交必须在 app fd 上进行，所以本步骤
            // 在 app fd 上执行 CLAIMINTERFACE + SETINTERFACE。
            //
            // USBFS 层面四种可能结果：
            //   CLAIMINTERFACE rc=0  + SETINTERFACE rc=0  → PASS
            //   CLAIMINTERFACE rc=0  + SETINTERFACE 失败  → FAIL
            //   CLAIMINTERFACE EBUSY + SETINTERFACE rc=0  → PASS（已被持有但 alt 已设置）
            //   其他                                        → FAIL
            onStep("步骤9/10: app fd USBFS claim...")
        onLog(DiagLine.Section("--- 步骤8: app fd 完成 USBFS claim (Phase 4) ---"))

            val verifyResult = usbSource.claimInterfaceNative(
                fd = conn.fileDescriptor,
                interfaceId = finalConfig.interfaceId,
                altSetting = finalConfig.alternateSetting
            )
            for (line in verifyResult.lines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                when {
                    trimmed.contains("CLAIMINTERFACE: rc=0") ->
                        onLog(DiagLine.Ok("  $trimmed"))
                    trimmed.contains("CLAIMINTERFACE") && trimmed.contains("errno=16") ->
                        onLog(DiagLine.Warn("  $trimmed  ← 已被占用"))
                    trimmed.contains("CLAIMINTERFACE") && trimmed.contains("FAIL") ->
                        onLog(DiagLine.Fail("  $trimmed"))
                    trimmed.contains("SETINTERFACE: rc=0") ->
                        onLog(DiagLine.Ok("  $trimmed"))
                    trimmed.contains("SETINTERFACE") && trimmed.contains("FAIL") ->
                        onLog(DiagLine.Fail("  $trimmed"))
                    trimmed.startsWith("SUCCESS") ->
                        onLog(DiagLine.Ok("  $trimmed"))
                    else -> onLog(DiagLine.Info("  $trimmed"))
                }
            }

            val claimOk = verifyResult.contains("CLAIMINTERFACE: rc=0")
            val claimBusy = verifyResult.contains("CLAIMINTERFACE") && verifyResult.contains("errno=16")
            val setIfOk = verifyResult.contains("SETINTERFACE: rc=0")

            if (claimOk && setIfOk) {
                onLog(DiagLine.Section("=== Phase 4 全部通过 ==="))
                onLog(DiagLine.Ok("  app fd 已成功 claim + SETINTERFACE"))
                onLog(DiagLine.Ok("  → USBFS 层面接口已就绪，可进入 Phase 5 (URB 提交)"))
            } else if (claimBusy && setIfOk) {
                onLog(DiagLine.Section("=== Phase 4 通过（接口已被持有）==="))
                onLog(DiagLine.Warn("  CLAIMINTERFACE EBUSY → system_server 持有 claim"))
                onLog(DiagLine.Warn("  SETINTERFACE rc=0 → alt setting 已正确"))
                onLog(DiagLine.Warn("  → app fd 未持有 claim，需迁移"))

                // ── 步骤 8b: 迁移 claim 到 app fd ─────────────────────
                onStep("步骤9b/10: 迁移 claim 到 app fd...")
        onLog(DiagLine.Section("--- 步骤8b: 迁移 claim 到 app fd ---"))
                onLog(DiagLine.Info("  释放 system_server claim → 在 app fd 上 CLAIMINTERFACE"))
                val ensured = usbSource.ensureAppFdClaim(conn, targetInterface)
                if (ensured) {
                    onLog(DiagLine.Ok("  app fd 现已持有 interface claim — 内核驱动无法 rebind"))
                } else {
                    onLog(DiagLine.Warn("  app fd 仍无法持有 claim（回退到 system_server）"))
                    onLog(DiagLine.Warn("  → Phase 5/6 可能因 claim 丢失而在中途失败"))
                }
            } else if (claimOk && !setIfOk) {
                onLog(DiagLine.Fail("FAIL: claim 成功但 SETINTERFACE 失败"))
                onLog(DiagLine.Info("  → 等时端点未激活，无法进行 Phase 5"))
            } else {
                onLog(DiagLine.Fail("FAIL: CLAIMINTERFACE 失败且 SETINTERFACE 失败"))
                onLog(DiagLine.Info("  → 接口完全无法接管，Phase 4 失败"))
            }

            // ── 步骤 9: 解除麦克风静音 (UAC 2.0 Feature Unit Mute) ──
            val canTryPhase5 = (claimOk || claimBusy) && setIfOk
            if (canTryPhase5) {
                // 9a-claim: 接管 AC Interface（controlTransfer 发送 Feature Unit SET_CUR 前必须 claim）
                onStep("步骤10/10-1: 接管 AC Interface...")
        onLog(DiagLine.Section("--- 步骤9a-claim: 接管 AC Interface ---"))
                val acClaimResult = usbSource.claimAcInterface(device, conn)
                for (line in acClaimResult.lines()) {
                    val t = line.trim()
                    if (t.isEmpty()) continue
                    when {
                        t.contains("OK") -> onLog(DiagLine.Ok("  $t"))
                        t.contains("FAIL") -> onLog(DiagLine.Fail("  $t"))
                        t.startsWith("⚠") -> onLog(DiagLine.Warn("  $t"))
                        else -> onLog(DiagLine.Info("  $t"))
                    }
                }

                // 9b: 解除 Feature Unit 静音（GET_CUR 诊断 + SET_CUR）
                onStep("步骤10/10-2: Feature Unit 诊断 (Mute+Vol)...")
        onLog(DiagLine.Section("--- 步骤9b: 解除 Feature Unit 静音 ---"))
                val unmuteResult = usbSource.unmuteAllFeatureUnits(conn)
                for (line in unmuteResult.lines()) {
                    val t = line.trim()
                    if (t.isEmpty()) continue
                    when {
                        // 全零根因: Vol=0
                        t.contains("Vol=0") || t.contains("全零根因") ->
                            onLog(DiagLine.Fail("  $t"))
                        // Mute 已关闭（好）
                        t.contains("Mute=0(unmuted)") ->
                            onLog(DiagLine.Ok("  $t"))
                        // Mute 开启（设备被静音）
                        t.contains("MUTED!") ->
                            onLog(DiagLine.Fail("  $t"))
                        // SET_Mute JNI 成功: rc=2 (UAC2) or rc=1 (UAC1)
                        t.contains("SET_Mute=rc=2") || t.contains("SET_Mute=rc=1") ->
                            onLog(DiagLine.Ok("  $t"))
                        // SET_Mute JNI 失败
                        t.contains("SET_Mute=rc=-1") ->
                            onLog(DiagLine.Warn("  $t  ← JNI ioctl 返回 EPERM，内核 USBFS 拦截"))
                        t.contains("SET_Mute=rc=-32") ->
                            onLog(DiagLine.Fail("  $t  ← 设备 STALL"))
                        // Vol GET 成功、非零
                        t.contains("Vol=") && t.contains("dB") && !t.contains("Vol=0") ->
                            onLog(DiagLine.Ok("  $t"))
                        // Vol GET 返回 STALL
                        t.contains("Vol=rc=-32") ->
                            onLog(DiagLine.Info("  $t  ← FU 不支持 Volume 控制"))
                        // Vol GET 返回 EPERM
                        t.contains("Vol=rc=-1") ->
                            onLog(DiagLine.Warn("  $t"))
                        // wIndex 信息
                        t.contains("wIndex=") ->
                            onLog(DiagLine.Info("  $t"))
                        t.startsWith("⚠") ->
                            onLog(DiagLine.Warn("  $t"))
                        // UAC 版本 / 格式提示
                        t.startsWith("UAC") || t.startsWith("  AC Interface") ||
                        t.startsWith("  Feature Units") || t.startsWith("  数据格式") ->
                            onLog(DiagLine.Info("  $t"))
                        t.startsWith("  ── FU") ->
                            onLog(DiagLine.Info("  $t"))
                        else -> onLog(DiagLine.Info("  $t"))
                    }
                }

                // 9c-clock: 探测 Clock Source 状态（GET_CUR，Device→Host 不需要 bypass）
                onStep("步骤10/10-3: Clock Source 探测...")
        onLog(DiagLine.Section("--- 步骤9c-clock: Clock Source 探测 ---"))
                val clockResult = usbSource.probeClockStatus(conn)
                for (line in clockResult.lines()) {
                    val t = line.trim()
                    if (t.isEmpty()) continue
                    when {
                        t.contains("INVALID") ->
                            onLog(DiagLine.Fail("  $t"))
                        t.contains("CurFreq:") ->
                            onLog(DiagLine.Ok("  $t"))
                        t.contains("ClockValid:") ->
                            onLog(DiagLine.Ok("  $t"))
                        t.startsWith("⚠") ->
                            onLog(DiagLine.Warn("  $t"))
                        else -> onLog(DiagLine.Info("  $t"))
                    }
                }

                // 9d: 首次 URB（仅关键状态，不 dump 数据）
                onStep("步骤10/10-4: 首次 URB 提交 (REAPURB 轮询中)...")
        onLog(DiagLine.Section("--- 步骤9d: 首次 URB 提交 (Phase 5) ---"))
                onLog(DiagLine.Info("  ep=0x%02X mps=%d packets=8"
                    .format(finalConfig.endpointAddress, finalConfig.maxPacketSize)))

                val urbResult = usbSource.readOneUrb(
                    fd = conn.fileDescriptor,
                    endpointAddress = finalConfig.endpointAddress,
                    maxPacketSize = finalConfig.maxPacketSize,
                    numPackets = 8,
                    timeoutMs = 4000
                )

                // 首次 URB: 只显示关键状态行，不 dump hex（预热期全零无信息量）
                for (line in urbResult.lines()) {
                    val t = line.trim()
                    if (t.isEmpty()) continue
                    when {
                        t.startsWith("SUBMITURB: rc=0")       -> onLog(DiagLine.Ok("  $t"))
                        t.startsWith("SUBMITURB:")            -> onLog(DiagLine.Fail("  $t"))
                        t.startsWith("REAPURB ioctl rc: 0")   -> onLog(DiagLine.Ok("  $t"))
                        t.startsWith("REAPURB ioctl rc:")     -> onLog(DiagLine.Fail("  $t"))
                        t.startsWith("urb->status=0")         -> onLog(DiagLine.Ok("  $t"))
                        t.startsWith("urb->status=")          -> onLog(DiagLine.Fail("  $t"))
                        t.contains("err=0/")          -> onLog(DiagLine.Ok("  $t"))
                        t.contains("err=")            -> onLog(DiagLine.Warn("  $t"))
                        t.startsWith("ptr:")                  -> onLog(DiagLine.Info("  $t"))
                        t.startsWith("iso[")                  -> onLog(DiagLine.Info("  $t"))
                        t.startsWith("est rate:")             -> onLog(DiagLine.Info("  $t"))
                        t.startsWith("TIMEOUT:")              -> onLog(DiagLine.Fail("  $t"))
                        t.startsWith("→")                     -> onLog(DiagLine.Warn("  $t"))
                        // skip RAW, packed, hex dump lines in first URB
                        else -> {}  // silently skip
                    }
                }

                // 判断 Phase 5 结果
                when {
                    urbResult.contains("SUBMITURB: rc=0") &&
                    urbResult.contains("urb->status=0") &&
                    urbResult.contains("err=0") -> {
                        onLog(DiagLine.Section("=== Phase 5 (URB 提交) 通过 ==="))
                        onLog(DiagLine.Ok("  等时传输链路已打通！USB 设备正在发送 PCM 数据"))
                        onLog(DiagLine.Info("  下一步: Phase 6 — 连续读取循环 + PCM 解交织"))
                    }
                    else -> {
                        onLog(DiagLine.Fail("  Phase 5 未完全通过，请查看上方诊断细节"))
                    }
                }

                // 二次 URB 读取 — 排除 DSP 预热延迟
                onStep("步骤10/10-5: 二次 URB 读取 (hex dump)...")
        onLog(DiagLine.Section("--- 步骤9e: 二次 URB 读取 (hex dump 验证) ---"))
                val urbResult2 = usbSource.readOneUrb(
                    fd = conn.fileDescriptor,
                    endpointAddress = finalConfig.endpointAddress,
                    maxPacketSize = finalConfig.maxPacketSize,
                    numPackets = 8,
                    timeoutMs = 4000
                )
                var secondUrbNonZero = false
                for (line in urbResult2.lines()) {
                    val t = line.trim()
                    if (t.isEmpty()) continue
                    // 检查 hex dump 中是否有非00字节（t 已经 trim 过，无前导空格）
                    if (t.matches(Regex("^[0-9A-F]{2} .*"))) {
                        val bytes = t.split(" ").filter { it.length == 2 && it != "00" }
                        if (bytes.isNotEmpty()) secondUrbNonZero = true
                    }
                    when {
                        t.startsWith("ptr:") && t.contains("same") -> onLog(DiagLine.Info("  $t"))
                        t.startsWith("ptr:") -> onLog(DiagLine.Warn("  $t"))
                        t.startsWith("urb->status=0") -> onLog(DiagLine.Ok("  $t"))
                        t.startsWith("urb->status=") -> onLog(DiagLine.Fail("  $t"))
                        t.contains("error_count=0/") -> onLog(DiagLine.Ok("  $t"))
                        t.startsWith("est rate:") -> onLog(DiagLine.Info("  $t"))
                        t.startsWith("RAW") || t.startsWith("packed") -> onLog(DiagLine.Info("  $t"))
                        t.startsWith("TIMEOUT:") -> onLog(DiagLine.Fail("  $t"))
                        t.startsWith("→") -> onLog(DiagLine.Warn("  $t"))
                        t.matches(Regex("^[0-9A-F]{2} .*")) -> {
                            val hasNonZero = t.split(" ").any { it.length == 2 && it != "00" }
                            if (hasNonZero) onLog(DiagLine.Ok("  $t ← 非零!"))
                            else onLog(DiagLine.Info("  $t"))
                        }
                        else -> onLog(DiagLine.Info("  $t"))
                    }
                }
                if (secondUrbNonZero) {
                    onLog(DiagLine.Ok("  ★ 二次 URB 出现非零数据! DSP 需要预热时间"))
                } else {
                    onLog(DiagLine.Warn("  二次 URB 仍全零 — DSP 预热不是根因"))
                    onLog(DiagLine.Info("  → 根因可能是 XVF3800 需要播放流(OUT)激活才输出捕获数据"))
                    onLog(DiagLine.Info("  → 或固件需要 vendor-specific 初始化命令"))
                }

                // ── 步骤 10: Phase 6 连续等时采集 ──────────────────
                onStep("步骤10/10-6: 连续等时采集 (Phase 6)...")
        onLog(DiagLine.Section("--- 步骤10: Phase 6 连续等时采集 (2秒, 30帧) ---"))
                onLog(DiagLine.Info("  ep=0x%02X mps=%d packets=8 maxFrames=30"
                    .format(finalConfig.endpointAddress, finalConfig.maxPacketSize)))
                onLog(DiagLine.Info("  → 每帧 RMS 显示在下方「关键日志」区域，无需 logcat"))

                trace("PHASE6_CALL_START")
                val phase6Result = try {
                    usbSource.runStreamingDiagnosis(
                        fd = conn.fileDescriptor,
                        endpointAddress = finalConfig.endpointAddress,
                        maxPacketSize = finalConfig.maxPacketSize,
                        numPackets = 8,
                        maxFrames = 30,
                        onStatus = { msg -> onPhase6Rms(msg) },
                        trace = { msg -> trace(msg) }
                    )
                } catch (e: Exception) {
                    "FAIL: Phase 6 exception: ${e.message}"
                }
                trace("PHASE6_RESULT: $phase6Result")

                when {
                    phase6Result.startsWith("PASS:") -> {
                        onLog(DiagLine.Section("=== Phase 6 (连续采集) 通过 ==="))
                        onLog(DiagLine.Ok("  $phase6Result"))
                        onLog(DiagLine.Info("  → 等时传输链路完整，PCM 数据正常流动"))
                        onLog(DiagLine.Info("  → 下一步: 接入 audioStream() 实现实时波束成形"))
                    }
                    phase6Result.startsWith("WARN:") -> {
                        onLog(DiagLine.Warn("  $phase6Result"))
                        onLog(DiagLine.Info("  → 即使全零，URB 传输机制本身可能正常"))
                        onLog(DiagLine.Info("  → 用 Audacity 验证 /sdcard/Download/usb_raw_4ch.pcm"))
                    }
                    phase6Result.startsWith("FAIL:") -> {
                        onLog(DiagLine.Fail("  $phase6Result"))
                    }
                }

                // ── E2E Test 2: 通道独立性验证（互相关测试）─────────
                if (!phase6Result.startsWith("FAIL:")) {
                    onStep("E2E Test 2: 通道互相关验证...")
                    onLog(DiagLine.Section("--- E2E Test 2: 通道互相关验证 (ch0 vs ch1) ---"))
                    onLog(DiagLine.Info("  收集 10 帧 (10240 点/通道)，计算归一化互相关"))
                    onLog(DiagLine.Info("  lag 搜索范围 ±5 (阵列对角线 90mm @ 16kHz 最大时延 4.2 采样点)"))
                    try {
                        val ccResult = crossCorrelationTest(usbSource)
                        for (line in ccResult.lines()) {
                            val t = line.trim()
                            if (t.isEmpty()) continue
                            when {
                                t.contains("PASS") -> onLog(DiagLine.Ok("  $t"))
                                t.contains("FAIL") -> onLog(DiagLine.Fail("  $t"))
                                t.contains("WARN") -> onLog(DiagLine.Warn("  $t"))
                                else -> onLog(DiagLine.Info("  $t"))
                            }
                        }
                    } catch (e: Exception) {
                        onLog(DiagLine.Fail("  E2E Test 2 异常: ${e.message}"))
                    }
                }
            }
        } else {
            onLog(DiagLine.Fail("FAIL: claimInterface 失败"))
            onLog(DiagLine.Info("可能原因:"))
            onLog(DiagLine.Info("  1. 内核音频驱动 (snd-usb-audio) 占用接口"))
            onLog(DiagLine.Info("  2. Android USB Audio HAL 已独占设备"))
            onLog(DiagLine.Info("  3. 需先关闭所有使用麦克风的 App 后重试"))
        }
    } finally {
        usbSource.release()
    }
}

// ─── UI 组件 ──────────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun DiagWarning(text: String) {
    Text(text, color = Color(0xFFFFA000), fontSize = 13.sp, lineHeight = 18.sp)
}

@Composable
private fun DiagInfoCard(title: String, items: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A237E).copy(alpha = 0.08f)),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 13.sp, color = Color(0xFF90CAF9))
            items.forEach { item ->
                Text(item, fontSize = 12.sp, color = Color(0xFFB0BEC5), fontFamily = FontFamily.Monospace)
            }
        }
    }
}
