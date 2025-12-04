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

/**
 * 频谱图显示组件
 *
 * @param data 频域幅值数组 (FFT Magnitude)
 * @param sampleRate 采样率，默认 44100Hz，用于计算横轴频率刻度
 */
@Composable
fun SpectrumView(
    data: FloatArray,
    sampleRate: Int = 44100,
    modifier: Modifier = Modifier
) {
    // 文本测量器，用于在 Canvas 中绘制文字
    val textMeasurer = rememberTextMeasurer()

    // 容器 Box：设置背景色
    Box(
        modifier = modifier
            .background(Color(0xFF1E1E1E))
            .padding(4.dp)
    ) {
        // 如果数据为空，显示提示文字
        if (data.isEmpty()) {
            Text("无数据", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
            return@Box
        }

        // 核心绘图区域
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            // 1. 布局参数配置 (Margins)
            val paddingLeft = 50.dp.toPx()    // 左边距：留给 Y 轴数值
            val paddingBottom = 30.dp.toPx()  // 下边距：留给 X 轴数值
            val paddingRight = 20.dp.toPx()   // 右边距：防止文字溢出
            val paddingTop = 20.dp.toPx()     // 上边距：留给峰值标签

            // 实际图表区域的宽高
            val chartWidth = width - paddingLeft - paddingRight
            val chartHeight = height - paddingBottom - paddingTop


            // 2. 数据预处理
            // data 长度通常是 N/2 (Nyquist)。我们只显示前一半，即 0 ~ 11025Hz
            val displayDataSize = data.size / 2
            // X轴最大频率值
            val maxFreq = sampleRate / 4

            // 寻找峰值 (用于标注)
            val peakIndex = data.take(displayDataSize).indices.maxByOrNull { data[it] } ?: 0
            val peakValRaw = data[peakIndex]

            // 寻找当前视图内的最大值 (用于 Y 轴归一化)
            val maxValInView = data.take(displayDataSize).maxOrNull() ?: 1f
            // 防止除以 0 崩溃
            val safeMax = if (maxValInView == 0f) 1f else maxValInView


            // 3. 绘制背景网格 (Grid) - 最底层
            val rows = 5 // Y轴行数
            val cols = 5 // X轴列数
            val gridColor = Color.White.copy(alpha = 0.15f) // 浅白色，低透明度
            val axisTextStyle = TextStyle(color = Color.Gray, fontSize = 10.sp)

            // --- A. 画横线 (Y轴) ---
            for (i in 0..rows) {
                // 计算 Y 坐标：注意 Canvas 0点在顶部，所以要用减法
                val y = paddingTop + (chartHeight - (i.toFloat() / rows) * chartHeight)

                // 画线
                drawLine(
                    color = gridColor,
                    start = Offset(paddingLeft, y),
                    end = Offset(width - paddingRight, y),
                    strokeWidth = 1f
                )

                // 画文字 (数值)
                val labelValue = (safeMax / rows * i)
                val labelText = "%.1f".format(labelValue)
                val textLayout = textMeasurer.measure(labelText, style = axisTextStyle)
                // 文字右对齐
                drawText(
                    textLayoutResult = textLayout,
                    topLeft = Offset(paddingLeft - textLayout.size.width - 10f, y - textLayout.size.height / 2)
                )
            }

            // --- B. 画竖线 (X轴) ---
            for (i in 0..cols) {
                val x = paddingLeft + (i.toFloat() / cols) * chartWidth

                // 画线
                drawLine(
                    color = gridColor,
                    start = Offset(x, paddingTop),
                    end = Offset(x, paddingTop + chartHeight),
                    strokeWidth = 1f
                )

                // 画文字 (Hz)
                val freqValue = (maxFreq / cols * i)
                val labelText = "${freqValue}Hz"
                val textLayout = textMeasurer.measure(labelText, style = axisTextStyle)
                val textWidth = textLayout.size.width

                // 智能调整文字 X 位置，防止边缘溢出
                val textX = when (i) {
                    0 -> x // 第一个靠左
                    cols -> x - textWidth // 最后一个靠右
                    else -> x - textWidth / 2 // 中间的居中
                }

                drawText(
                    textLayoutResult = textLayout,
                    topLeft = Offset(textX, paddingTop + chartHeight + 10f)
                )
            }


            // 4. 绘制波形 (Waveform) - 中间层
            val path = Path()
            // 移动到起点 (左下角)
            path.moveTo(paddingLeft, paddingTop + chartHeight)

            for (i in 0 until displayDataSize) {
                // 映射 X 坐标
                val x = paddingLeft + (i.toFloat() / displayDataSize) * chartWidth
                // 映射 Y 坐标 (归一化)
                val normalizedVal = (data[i] / safeMax) * chartHeight
                // 翻转 Y 轴 (因为屏幕坐标向下为正)
                val y = paddingTop + (chartHeight - normalizedVal)
                path.lineTo(x, y)
            }

            // --- 配色方案优化 ---
            // 1. 填充渐变 (Fill)：从半透明青色 -> 透明
            // 为了闭合路径进行填充，我们需要连接回右下角和左下角
            val fillPath = Path()
            fillPath.addPath(path)
            fillPath.lineTo(paddingLeft + chartWidth, paddingTop + chartHeight) // 右下
            fillPath.lineTo(paddingLeft, paddingTop + chartHeight) // 左下
            fillPath.close()

            val fillGradient = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF00FFFF).copy(alpha = 0.5f), // 顶部：高亮青色 (半透明)
                    Color(0xFF0088FF).copy(alpha = 0.2f), // 中间：深蓝色 (弱透明)
                    Color.Transparent                     // 底部：完全透明
                ),
                startY = paddingTop,
                endY = paddingTop + chartHeight
            )
            drawPath(path = fillPath, brush = fillGradient)

            // 2. 描边线条 (Stroke)：亮青色实线，由线条勾勒轮廓，最清晰
            drawPath(
                path = path,
                color = Color(0xFF00FFFF), // 纯青色，类似示波器效果
                style = Stroke(width = 3f)
            )

            // ==========================================================
            // 5. 绘制坐标轴实线 (Axes) - 上层
            // ==========================================================
            // 绘制白色实线，确保覆盖在波形图上方，保持边框清晰
            // Y轴实线
            drawLine(Color.White, Offset(paddingLeft, paddingTop), Offset(paddingLeft, paddingTop + chartHeight), 2f)
            // X轴实线
            drawLine(Color.White, Offset(paddingLeft, paddingTop + chartHeight), Offset(width - paddingRight, paddingTop + chartHeight), 2f)


            // ==========================================================
            // 6. 绘制峰值标注 (Peak Marker) - 最顶层
            // ==========================================================
            val peakX = paddingLeft + (peakIndex.toFloat() / displayDataSize) * chartWidth
            val peakY = paddingTop + (chartHeight - (peakValRaw / safeMax) * chartHeight)
            val peakFreq = (peakIndex.toFloat() / displayDataSize) * maxFreq

            // 画白色外圈
            drawCircle(
                color = Color.White,
                radius = 6.dp.toPx(),
                center = Offset(peakX, peakY),
                style = Stroke(width = 2.dp.toPx())
            )
            // 画红色内点
            drawCircle(
                color = Color.Red,
                radius = 3.dp.toPx(),
                center = Offset(peakX, peakY)
            )

            // 画峰值文字标签
            val peakText = "Peak: ${peakFreq.toInt()}Hz"
            val peakTextStyle = TextStyle(
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                background = Color.Black.copy(alpha = 0.7f) // 加深背景色，防止被波形干扰
            )
            val peakLayout = textMeasurer.measure(peakText, style = peakTextStyle)

            // 智能调整标签位置
            var textDrawX = peakX + 20f
            var textDrawY = peakY - 40f

            // 如果靠右，字写在左边
            if (peakX > width * 0.8f) {
                textDrawX = peakX - peakLayout.size.width - 20f
            }
            // 如果靠上，字写在下边
            if (peakY < paddingTop + 30f) {
                textDrawY = peakY + 30f
            }

            drawText(
                textLayoutResult = peakLayout,
                topLeft = Offset(textDrawX, textDrawY)
            )
        }
    }
}