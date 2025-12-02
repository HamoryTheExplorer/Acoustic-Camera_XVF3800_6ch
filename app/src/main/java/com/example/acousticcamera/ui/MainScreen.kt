package com.example.acousticcamera.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.example.acousticcamera.viewmodel.MainViewModel

@Composable
fun MainScreen(
    onBackClick: () -> Unit, // <--- 新增参数：接收返回指令
    viewModel: MainViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val status by viewModel.statusText.collectAsState()
    val spectrum by viewModel.spectrumData.collectAsState()

    // 热力图状态
    val heatmapData by viewModel.heatmapData.collectAsState() // 拿到热力图数据
    var showHeatmapDialog by remember { mutableStateOf(false) } // 控制弹窗显示的 State

    // 关键逻辑：如果有真实数据，就用真实的；如果没有(null)，就造一个全为0的假数组。
    // 长度设为 4096 是为了让 Grid 计算时分母不为0，保证坐标轴能画出来。
    val displayData = remember(spectrum) {
        spectrum ?: FloatArray(4096) { 0f }
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

        // --- 上半部分：频谱图 ---
        SpectrumView(
            data = displayData,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f) // 让图表撑满剩余空间
                .padding(start = 16.dp, end = 16.dp)
        )

        // --- 下半部分：控制区 ---
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
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
            // 放在操作按钮上方，作为查看结果的入口
            Button(
                onClick = {
                    // 点击显示弹窗
                    showHeatmapDialog = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                // 只有当有数据时(spectrum != null)才允许点击
                enabled = spectrum != null
            ) {
                Text("显示声压热力图 (Heatmap)")
            }

            Spacer(modifier = Modifier.height(16.dp)) // 增加一点间距

            // --- 两个操作按钮 ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                // 两个按钮之间留出 16dp 的间距
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 左边：重置按钮 (次要操作，用空心样式)
                OutlinedButton(
                    onClick = { viewModel.resetAnalysis() },
                    modifier = Modifier
                        .weight(1f) // 占宽度的 50%
                        .height(56.dp)
                ) {
                    Text("重置")
                }

                // 右边：运行按钮 (主要操作，用实心样式)
                Button(
                    onClick = { viewModel.runAnalysis() },
                    modifier = Modifier
                        .weight(1f) // 占宽度的 50%
                        .height(56.dp)
                ) {
                    Text("运行分析")
                }
            }
        }
    }

    // --- 弹窗逻辑  ---
    if (showHeatmapDialog && heatmapData != null) {
        Dialog(onDismissRequest = { showHeatmapDialog = false }) {
            // 弹窗内容
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White, shape = RoundedCornerShape(16.dp))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "声源定位结果",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.Black,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // 放置我们的热力图组件
                HeatmapView(
                    heatmapData = heatmapData!!,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(onClick = { showHeatmapDialog = false }) {
                    Text("关闭")
                }
            }
        }
    }

}