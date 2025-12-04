package com.example.acousticcamera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
            AcousticCameraTheme {
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
                        // --- 进入 Analysis 界面 ---
                        // 1. 物理返回键：按下返回键就去 Home
                        BackHandler(enabled = true) {
                            currentScreen = "Home"
                        }

                        // 2. 显示主界面，点击左上角箭头返回Home
                        MainScreen(
                            onBackClick = { currentScreen = "Home" }
                        )
                    }
                }
            }
        }
    }
}