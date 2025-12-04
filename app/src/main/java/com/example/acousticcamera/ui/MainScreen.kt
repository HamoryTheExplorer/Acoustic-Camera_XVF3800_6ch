package com.example.acousticcamera.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.example.acousticcamera.viewmodel.MainViewModel

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    onBackClick: () -> Unit, // 接收返回指令
    viewModel: MainViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    // 三个参数的状态
    val status by viewModel.statusText.collectAsState()
    val spectrum by viewModel.spectrumData.collectAsState()
    val heatmapData by viewModel.heatmapData.collectAsState() // 拿到热力图数据

    // 控制热力图是否显示的开关 (点击按钮才展开)
    var isHeatmapVisible by remember { mutableStateOf(false) }

    // 如果有真实数据，就用真实的；如果没有(null)，就造一个全为0的假数组。
    // 长度设为 4096，让 Grid 计算时分母不为0，保证坐标轴能画出来。
    val displayData = remember(spectrum) {
        spectrum ?: FloatArray(4096) { 0f }
    }
    // 如果重置了数据，同时也把热力图收起来
    if (spectrum == null && isHeatmapVisible) {
        isHeatmapVisible = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding() // 避免被底部小白条遮挡
            .padding(top = 16.dp) // 给顶部状态栏留点空隙
    ) {
        // --- 顶部标题栏 ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 返回按钮
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface // 自动适配深色/浅色模式
                )
            }

            // 标题
            Text(
                text = "信号分析",
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        // 使用 CompositionLocalProvider 禁用滚动时回弹效果
        CompositionLocalProvider(
            LocalOverscrollFactory provides null
        ) {
            // --- 2. 可滚动的内容区域 ---
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f) // 占满剩余空间，内部内容过多时可滚动
                    .verticalScroll(rememberScrollState()), // 添加滚动
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // --- 上半部分：频谱图 ---
                SpectrumView(
                    data = displayData,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp) // 固定高度
                        .padding(horizontal = 16.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // --- B. 热力图区域 (嵌入式) ---
                // 只有当点击了"显示热力图"按钮，并且有数据时才显示
                if (isHeatmapVisible && heatmapData != null) {
                    HeatmapView(
                        heatmapData = heatmapData!!,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                        // 让热力图保持正方形，也可以指定 height(300.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                } else if (isHeatmapVisible && heatmapData == null) {
                    // 如果用户点了显示，但还没算完数据，显示个加载占位
                    Text("正在计算热力图...", color = Color.Gray)
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }

        // --- 下半部分：控制区 ---
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // --- 显示热力图按钮 ---
            Button(
                onClick = {
                    isHeatmapVisible = !isHeatmapVisible // 切换显示/隐藏
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                // 热力图算好了才能点
                enabled = heatmapData != null
            ) {
                Text(if (isHeatmapVisible) "隐藏声压热力图" else "显示声压热力图")
            }

            Spacer(modifier = Modifier.height(16.dp)) // 间距

            // --- 两个操作按钮 ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                // 两个按钮之间留出 16dp 的间距
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 左边：重置按钮 (次要操作，用空心样式)
                OutlinedButton(
                    onClick = {
                        viewModel.resetAnalysis()
                        isHeatmapVisible = false // 重置时自动隐藏热力图
                    },
                    modifier = Modifier
                        .weight(1f) // 占宽度的 50%
                        .height(56.dp)
                ) {
                    Text("重置")
                }

                // 右边：运行按钮 (主要操作，用实心样式)
                Button(
                    onClick = {
                        isHeatmapVisible = false
                        viewModel.runAnalysis() },
                    modifier = Modifier
                        .weight(1f) // 占宽度的 50%
                        .height(56.dp)
                ) {
                    Text("运行分析")
                }
            }
            //Spacer(modifier = Modifier.height(20.dp))
        }
    }

}