package com.example.acousticcamera.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.acousticcamera.data.MicDeviceInfo
import com.example.acousticcamera.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    viewModel: MainViewModel = viewModel()
) {
    val availableMics  by viewModel.availableMicDevices.collectAsState()
    val selectedMicId  by viewModel.selectedMicDeviceId.collectAsState()

    // 进入界面时刷新设备列表
    LaunchedEffect(Unit) { viewModel.refreshMicDevices() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor    = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // ── 节标题 ──────────────────────────────────────────────────────
            Text(
                "麦克风选择",
                style   = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            Text(
                "以下设置仅在硬件模式下生效。切换麦克风将停止当前分析。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            // ── 设备列表 ─────────────────────────────────────────────────────
            Column(modifier = Modifier.selectableGroup()) {
                // 系统默认（不指定设备）
                MicOptionRow(
                    name      = "系统默认",
                    typeLabel = "由 Android 自动选择",
                    selected  = selectedMicId == null,
                    onClick   = { viewModel.setSelectedMicDevice(null) }
                )
                HorizontalDivider(modifier = Modifier.padding(start = 56.dp))

                if (availableMics.isEmpty()) {
                    Text(
                        "未检测到可用麦克风设备\n请确认麦克风权限已授予，或点击下方刷新",
                        style    = MaterialTheme.typography.bodyMedium,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    availableMics.forEach { device ->
                        MicOptionRow(
                            name      = device.name,
                            typeLabel = device.typeLabel,
                            selected  = selectedMicId == device.id,
                            onClick   = { viewModel.setSelectedMicDevice(device.id) }
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            OutlinedButton(
                onClick  = { viewModel.refreshMicDevices() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("刷新设备列表")
            }

            Spacer(Modifier.height(8.dp))

            Text(
                "提示：XVF3800 等 USB 麦克风阵列连接后，点击【刷新】即可出现在列表中。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MicOptionRow(
    name: String,
    typeLabel: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp)
        ) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            if (typeLabel.isNotEmpty()) {
                Text(
                    typeLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
