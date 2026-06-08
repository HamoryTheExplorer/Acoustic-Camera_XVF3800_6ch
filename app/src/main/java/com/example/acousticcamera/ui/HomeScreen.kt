package com.example.acousticcamera.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HomeScreen(
    onEnterClick: () -> Unit,
    onUsbDiagnosisClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {}
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            // App 标题
            Text(
                text = "声学相机",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold
                )
            )

            Text(
                text = "Acoustic Camera Demo",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(60.dp))

            // 分析模式按钮
            Button(
                onClick = onEnterClick,
                modifier = Modifier.height(50.dp)
            ) {
                Text(text = "分析模式 (Analysis)", fontSize = 18.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ─── USB 直连模式按钮 ────────────────────────────────────────
            Button(
                onClick = onUsbDiagnosisClick,
                modifier = Modifier.height(50.dp)
            ) {
                Text(text = "USB 直连 (Direct)", fontSize = 18.sp)
            }

            // 底部留白，保证内容可滚动到头
            Spacer(modifier = Modifier.height(32.dp))
        }

        // ─── 右上角齿轮按钮：避开状态栏（电池/时间区域） ──────────────────
        IconButton(
            onClick = onSettingsClick,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 4.dp, end = 8.dp)
        ) {
            Icon(
                Icons.Default.Settings,
                contentDescription = "麦克风设置",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

