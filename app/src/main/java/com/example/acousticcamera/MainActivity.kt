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
import com.example.acousticcamera.ui.SettingsScreen
import com.example.acousticcamera.ui.UsbDiagnosisScreen
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
                    // 界面状态：Home / Analysis / Settings
                    var currentScreen by remember { mutableStateOf("Home") }
                    var previousScreen by remember { mutableStateOf("Home") }

                    when (currentScreen) {
                        "Home" -> {
                            HomeScreen(
                                onEnterClick        = { currentScreen = "Analysis" },
                                onUsbDiagnosisClick = { currentScreen = "UsbDiagnosis" },
                                onSettingsClick     = {
                                    previousScreen = "Home"
                                    currentScreen = "Settings"
                                }
                            )
                        }
                        "Analysis" -> {
                            BackHandler(enabled = true) { currentScreen = "Home" }
                            MainScreen(
                                onBackClick     = { currentScreen = "Home" },
                                onSettingsClick = {
                                    previousScreen = "Analysis"
                                    currentScreen = "Settings"
                                }
                            )
                        }
                        "Settings" -> {
                            BackHandler(enabled = true) { currentScreen = previousScreen }
                            SettingsScreen(
                                onBackClick = { currentScreen = previousScreen }
                            )
                        }
                        "UsbDiagnosis" -> {
                            BackHandler(enabled = true) { currentScreen = "Home" }
                            UsbDiagnosisScreen(
                                onBackClick = { currentScreen = "Home" },
                            )
                        }
                    }
                }
            }
        }
    }
}