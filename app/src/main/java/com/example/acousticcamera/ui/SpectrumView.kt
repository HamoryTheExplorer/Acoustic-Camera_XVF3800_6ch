package com.example.acousticcamera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SpectrumView(
    data: FloatArray,
    sampleRate: Int = 44100,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()

    Box(
        modifier = modifier
            .background(Color(0xFF1E1E1E))
            .padding(4.dp)
    ) {
        if (data.isEmpty()) {
            Text("无数据", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
            return@Box
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            // --- 1. 布局调整 ---
            // 增加 paddingRight，防止最右边的文字贴边太紧
            val paddingLeft = 50.dp.toPx()  // 左边留白写 Y 轴数值
            val paddingBottom = 30.dp.toPx()// 下边留白写 X 轴数值
            val paddingRight = 20.dp.toPx() // 新增右侧边距

            val chartWidth = width - paddingLeft - paddingRight
            val chartHeight = height - paddingBottom

            // --- 2. 数据准备 ---
            // 我们只显示前 1/4 的频谱 (通常声音主要集中在低频)
            // 频率范围: 0 ~ SampleRate/4 (例如 44100/4 = 11025Hz)
            // 修正：取 data 的一半，也就是总频段的 1/4 (0~11025Hz)
            val displayDataSize = data.size / 2
            val maxFreq = sampleRate / 4

            // 找出最大值及其索引 (用于绘制峰值)
            // indices.maxByOrNull 返回的是索引
            val peakIndex = data.take(displayDataSize).indices.maxByOrNull { data[it] } ?: 0
            val peakValRaw = data[peakIndex] // 原始幅值

            // 找出整体最大值用于归一化 Y 轴
            val maxValInView = data.take(displayDataSize).maxOrNull() ?: 1f
            val safeMax = if (maxValInView == 0f) 1f else maxValInView

            // --- 3. 绘制网格与坐标轴 ---
            val rows = 5
            val cols = 5
            val gridColor = Color.Gray.copy(alpha = 0.3f)
            val axisTextStyle = TextStyle(color = Color.LightGray, fontSize = 10.sp)

            // A. Y轴 (横线)
            for (i in 0..rows) {
                val y = chartHeight - (i.toFloat() / rows) * chartHeight

                drawLine(
                    color = gridColor,
                    start = Offset(paddingLeft, y),
                    end = Offset(width - paddingRight, y), // 线画到 paddingRight 截止
                    strokeWidth = 1f
                )

                // Y轴数值
                val labelValue = (safeMax / rows * i)
                val labelText = "%.1f".format(labelValue)

                // 测量文字宽度，为了让文字右对齐
                val textLayout = textMeasurer.measure(labelText, style = axisTextStyle)
                drawText(
                    textLayoutResult = textLayout,
                    topLeft = Offset(paddingLeft - textLayout.size.width - 10f, y - textLayout.size.height / 2)
                )
            }

            // B. X轴 (竖线) - 【修复 Bug 1：文字显示不全】
            for (i in 0..cols) {
                val x = paddingLeft + (i.toFloat() / cols) * chartWidth

                drawLine(
                    color = gridColor,
                    start = Offset(x, 0f),
                    end = Offset(x, chartHeight),
                    strokeWidth = 1f
                )

                val freqValue = (maxFreq / cols * i)
                val labelText = "${freqValue}Hz"
                val textLayout = textMeasurer.measure(labelText, style = axisTextStyle)
                val textWidth = textLayout.size.width

                // 智能计算 X 坐标偏移：
                // 如果是第一个(0Hz)，靠左画
                // 如果是最后一个(MaxHz)，往左移整个文字宽度（靠右对齐）
                // 中间的居中对齐
                val textX = when (i) {
                    0 -> x
                    cols -> x - textWidth // 关键修正：防止最右侧文字切出屏幕
                    else -> x - textWidth / 2
                }

                drawText(
                    textLayoutResult = textLayout,
                    topLeft = Offset(textX, chartHeight + 10f)
                )
            }

            // 坐标轴实线
            drawLine(Color.White, Offset(paddingLeft, 0f), Offset(paddingLeft, chartHeight), 2f)
            drawLine(Color.White, Offset(paddingLeft, chartHeight), Offset(width - paddingRight, chartHeight), 2f)

            // --- 4. 绘制波形 ---
            val path = Path()
            path.moveTo(paddingLeft, chartHeight)

            for (i in 0 until displayDataSize) {
                val x = paddingLeft + (i.toFloat() / displayDataSize) * chartWidth
                val normalizedVal = (data[i] / safeMax) * chartHeight
                val y = chartHeight - normalizedVal
                path.lineTo(x, y)
            }

            // --- 5. 上色 ---
            val gradient = Brush.verticalGradient(
                colors = listOf(Color.Green, Color.Yellow, Color.Red),
                startY = chartHeight,
                endY = 0f
            )

            drawPath(path = path, brush = gradient, style = Stroke(width = 3f))

            // --- 6. 绘制峰值标注 (Peak Indicator) - 【修复 Bug 2】 ---

            // 计算峰值的屏幕坐标
            val peakX = paddingLeft + (peakIndex.toFloat() / displayDataSize) * chartWidth
            val peakY = chartHeight - (peakValRaw / safeMax) * chartHeight
            val peakFreq = (peakIndex.toFloat() / displayDataSize) * maxFreq

            // 1. 画个圈圈
            drawCircle(
                color = Color.White,
                radius = 6.dp.toPx(),
                center = Offset(peakX, peakY),
                style = Stroke(width = 2.dp.toPx())
            )
            drawCircle(
                color = Color.Red,
                radius = 3.dp.toPx(),
                center = Offset(peakX, peakY)
            )

            // 2. 准备峰值文字
            val peakText = "Peak: ${peakFreq.toInt()}Hz"
            val peakTextStyle = TextStyle(
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                background = Color.Black.copy(alpha = 0.5f) // 半透明背景增加可读性
            )
            val peakLayout = textMeasurer.measure(peakText, style = peakTextStyle)

            // 3. 智能判断文字画在哪里（防止出界）
            // 默认画在点的右上方
            var textDrawX = peakX + 20f
            var textDrawY = peakY - 40f

            // 如果点太靠右，字就画在左边
            if (peakX > width * 0.8f) {
                textDrawX = peakX - peakLayout.size.width - 20f
            }
            // 如果点太靠上，字就画在下面
            if (peakY < 50f) {
                textDrawY = peakY + 30f
            }

            drawText(
                textLayoutResult = peakLayout,
                topLeft = Offset(textDrawX, textDrawY)
            )
        }
    }
}