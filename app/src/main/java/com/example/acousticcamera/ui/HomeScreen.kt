package com.example.acousticcamera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HomeScreen(
    onEnterClick: () -> Unit // 回调函数：当按钮被点击时通知 MainActivity
) {
    Column(
        modifier = Modifier
            .fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
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

        // 进入按钮
        Button(
            onClick = onEnterClick,
            modifier = Modifier.height(50.dp)
        ) {
            Text(text = "点击进入信号分析", fontSize = 18.sp)
        }
    }
}