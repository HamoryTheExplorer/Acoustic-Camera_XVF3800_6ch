package com.example.acousticcamera.ui

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
import com.example.acousticcamera.viewmodel.MainViewModel

@Composable
fun MainScreen(viewModel: MainViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val status by viewModel.statusText.collectAsState()
    val spectrum by viewModel.spectrumData.collectAsState()

    // 关键逻辑：如果有真实数据，就用真实的；如果没有(null)，就造一个全为0的假数组。
    // 长度设为 4096 是为了让 Grid 计算时分母不为0，保证坐标轴能画出来。
    val displayData = remember(spectrum) {
        spectrum ?: FloatArray(4096) { 0f }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding() // 避免被底部小白条遮挡
    ) {
        // --- 上半部分：频谱图 ---
        SpectrumView(
            data = displayData,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f) // 让图表撑满剩余空间
                .padding(top = 32.dp, start = 16.dp, end = 16.dp) //顶部 32.dp 不占用导航栏空间
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

            // --- 修改开始: Row 并排两个按钮 ---
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
            // --- 修改结束 ---
        }
    }
}