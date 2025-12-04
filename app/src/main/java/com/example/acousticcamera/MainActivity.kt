package com.example.acousticcamera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.acousticcamera.ui.HomeScreen
import com.example.acousticcamera.ui.MainScreen
import com.example.acousticcamera.ui.theme.AcousticCameraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AcousticCameraTheme(
                dynamicColor = false // 关闭动态颜色，使自定义颜色生效
            ) {
                // Surface 是为了让背景色生效（比如深色模式）
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background //导入更改后的颜色
                ) {
                    // 定义两种状态：Home (欢迎页) 和 Analysis (分析页)
                    var currentScreen by remember { mutableStateOf("Home") }

                    if (currentScreen == "Home") {
                        HomeScreen(
                            onEnterClick = { currentScreen = "Analysis" }
                        )
                    } else {
                        // --- Analysis 界面 ---
                        // 处理物理返回键
                        BackHandler(enabled = true) {
                            currentScreen = "Home"
                        }

                        // 显示主功能页
                        MainScreen(
                            onBackClick = { currentScreen = "Home" }
                        )
                    }
                }
            }
        }
    }
}