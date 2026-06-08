package com.example.acousticcamera.ui

import android.Manifest
import android.content.pm.PackageManager
import com.example.acousticcamera.data.AudioConfig
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.camera.view.PreviewView
import com.example.acousticcamera.data.AudioSourceMode
import com.example.acousticcamera.viewmodel.MainViewModel

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onBackClick: () -> Unit,
    onSettingsClick: () -> Unit = {},
    viewModel: MainViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val context = LocalContext.current

    // ─── ViewModel 状态 ────────────────────────────────────────────────────
    val status      by viewModel.statusText.collectAsState()
    val spectrum    by viewModel.spectrumData.collectAsState()
    val heatmapData by viewModel.heatmapData.collectAsState()
    val isRunning   by viewModel.isRunning.collectAsState()
    val fps         by viewModel.fps.collectAsState()
    val sourceMode  by viewModel.sourceMode.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    // ─── 本地 UI 状态 ──────────────────────────────────────────────────────
    var pendingStartAnalysis by remember { mutableStateOf(false) }
    var frozenFrame by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    // 记录触发权限请求的目标模式（硬件或 XVF3800），权限授予后切换
    var pendingMicMode by remember { mutableStateOf<AudioSourceMode?>(null) }

    // 麦克风权限 Launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val targetMode = pendingMicMode
        pendingMicMode = null
        if (granted && targetMode != null) {
            viewModel.setSourceMode(targetMode)
        }
    }

    // 摄像头权限 Launcher（权限授予后自动开始分析）
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingStartAnalysis) {
            pendingStartAnalysis = false
            viewModel.startRealTimeAnalysis()
        }
    }

    val displayData = remember(spectrum) {
        spectrum ?: FloatArray(AudioConfig.CHUNK_SIZE / 2) { 0f }
    }
    // ─── 硬件初始化失败对话框 ─────────────────────────────────────────────
    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = { Text("硬件连接失败") },
            text  = { Text(errorMessage ?: "") },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError() }) { Text("确定") }
            }
        )
    }

    // ─── 页面布局 ──────────────────────────────────────────────────────────
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("信号分析", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        CompositionLocalProvider(LocalOverscrollFactory provides null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                // ── A. 数据源选择器 ─────────────────────────────────────────
                Text(
                    "数据源",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    SegmentedButton(
                        selected = sourceMode == AudioSourceMode.SIMULATION,
                        onClick  = {
                            if (sourceMode != AudioSourceMode.SIMULATION) {
                                viewModel.setSourceMode(AudioSourceMode.SIMULATION)
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
                    ) { Text("仿真") }

                    SegmentedButton(
                        selected = sourceMode == AudioSourceMode.HARDWARE,
                        onClick  = {
                            if (sourceMode == AudioSourceMode.HARDWARE) return@SegmentedButton
                            val hasPermission = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasPermission) {
                                viewModel.setSourceMode(AudioSourceMode.HARDWARE)
                            } else {
                                pendingMicMode = AudioSourceMode.HARDWARE
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
                    ) { Text("硬件") }

                    SegmentedButton(
                        selected = sourceMode == AudioSourceMode.XVF3800,
                        onClick  = {
                            if (sourceMode == AudioSourceMode.XVF3800) return@SegmentedButton
                            val hasPermission = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasPermission) {
                                viewModel.setSourceMode(AudioSourceMode.XVF3800)
                            } else {
                                pendingMicMode = AudioSourceMode.XVF3800
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
                    ) { Text("XVF3800") }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // ── B. 频谱图 ───────────────────────────────────────────────
                Text(
                    "频谱图 (Spectrum)",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.titleMedium
                )
                SpectrumView(
                    data = displayData,
                    sampleRate = 16000,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .padding(horizontal = 16.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // ── C. 可视化区域（摄像头 + 热力图叠加，全程不分开）───────
                val showVisualization = isRunning || heatmapData != null

                if (showVisualization) {
                    val visTitle = if (isRunning) "摄像头 + 声压热力图 (叠加)" else "声压热力图 (定格)"
                    Text(
                        visTitle,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.titleMedium
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .border(2.dp, Color.Gray, RoundedCornerShape(8.dp))
                    ) {
                        if (isRunning) {
                            CameraPreview(
                                onPreviewViewReady = { pv -> previewView = pv },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else if (frozenFrame != null) {
                            Image(
                                bitmap = frozenFrame!!,
                                contentDescription = "定格画面",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.FillBounds
                            )
                        }
                        if (heatmapData != null) {
                            HeatmapView(
                                heatmapData = heatmapData!!,
                                fpsText     = fps,
                                overlayMode = isRunning || frozenFrame != null,
                                modifier    = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                // ── D. 控制区 ───────────────────────────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    // 重置 / 开始分析
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                frozenFrame = null
                                viewModel.resetAnalysis()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                        ) { Text("重置") }

                        Button(
                            onClick = {
                                if (isRunning) {
                                    previewView?.bitmap?.let { bmp ->
                                        frozenFrame = bmp.asImageBitmap()
                                    }
                                    viewModel.stopAnalysis()
                                } else {
                                    frozenFrame = null
                                    val hasCameraPermission = ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.CAMERA
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (hasCameraPermission) {
                                        viewModel.startRealTimeAnalysis()
                                    } else {
                                        pendingStartAnalysis = true
                                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                    }
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp),
                            colors = if (isRunning) {
                                ButtonDefaults.buttonColors(containerColor = Color(0xFFB00020))
                            } else {
                                ButtonDefaults.buttonColors()
                            }
                        ) { Text(if (isRunning) "停止分析" else "实时分析") }
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }
    }
}
