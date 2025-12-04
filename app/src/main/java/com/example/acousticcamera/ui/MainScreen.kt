package com.example.acousticcamera.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.acousticcamera.viewmodel.MainViewModel

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onBackClick: () -> Unit,
    viewModel: MainViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    // 状态管理
    val status by viewModel.statusText.collectAsState()
    val spectrum by viewModel.spectrumData.collectAsState()
    val heatmapData by viewModel.heatmapData.collectAsState()

    var isHeatmapVisible by remember { mutableStateOf(false) }

    val displayData = remember(spectrum) {
        spectrum ?: FloatArray(4096) { 0f }
    }

    if (spectrum == null && isHeatmapVisible) {
        isHeatmapVisible = false
    }

    // 使用 Scaffold 替换最外层的 Column/Surface
    // Scaffold 是页面的脚手架，负责组织 TopBar 和 Content
    Scaffold(
        // 配置 TopBar (顶栏)
        // 之前是 Row，现在直接用 Scaffold 提供的 topBar 插槽
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "信号分析",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        // 注意：较新版本的 Compose 推荐使用 AutoMirrored 图标以支持从右向左的语言
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                // 设置顶栏颜色：背景色跟随主题，内容色自适应
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
        // scaffold 可以配置 floatingActionButton 等，这里暂时不需要
    ) { innerPadding ->
        // 处理 Content (内容区域)

        // 保持之前的去除回弹效果配置
        CompositionLocalProvider(
            LocalOverscrollFactory provides null
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding) // 必须应用 innerPadding，否则内容会钻到顶栏下面
                    .verticalScroll(rememberScrollState()), // 保持滚动能力
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                // ================= 内容区域 =================
                //Spacer(modifier = Modifier.height(16.dp)) //顶部间距

                // --- A. 频谱图 ---
                Text(
                    "频谱图 (Spectrum)",
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.titleMedium
                )

                SpectrumView(
                    data = displayData,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .padding(horizontal = 16.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // --- B. 热力图区域 ---
                if (isHeatmapVisible && heatmapData != null) {
                    Text(
                        "声压热力图 (Beamforming)",
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.titleMedium
                    )

                    HeatmapView(
                        heatmapData = heatmapData!!,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    )

                    //Spacer(modifier = Modifier.height(16.dp))

                } else if (isHeatmapVisible && heatmapData == null) {
                    Text("正在计算热力图...", color = Color.Gray)

                    //Spacer(modifier = Modifier.height(16.dp))
                }

                // --- C. 底部按钮区域 ---
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 状态文字
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    // 显示/隐藏热力图按钮
                    val isBtnEnabled = heatmapData != null
                    Button(
                        onClick = { isHeatmapVisible = !isHeatmapVisible },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        enabled = isBtnEnabled
                    ) {
                        Text(if (isHeatmapVisible) "隐藏声压热力图" else "显示声压热力图")
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 重置与运行按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                viewModel.resetAnalysis()
                                isHeatmapVisible = false
                            },
                            modifier = Modifier.weight(1f).height(56.dp)
                        ) {
                            Text("重置")
                        }

                        Button(
                            onClick = {
                                isHeatmapVisible = false
                                viewModel.runAnalysis()
                            },
                            modifier = Modifier.weight(1f).height(56.dp)
                        ) {
                            Text("运行分析")
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp)) // 底部留白
                }
            }
        }
    }
}