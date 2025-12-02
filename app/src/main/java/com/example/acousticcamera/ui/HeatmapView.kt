package com.example.acousticcamera.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.acousticcamera.data.GridConfig

/**
 * 热力图组件
 * 使用 Android Bitmap 来操作像素，然后转为 Compose 的 ImageBitmap 显示
 */
@Composable
fun HeatmapView(
    heatmapData: FloatArray,
    modifier: Modifier = Modifier
) {
    // 1. 生成热力图 Bitmap (逻辑不变)
    val imageBitmap = remember(heatmapData) {
        val size = GridConfig.GRID_SIZE
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size)
        for (i in heatmapData.indices) {
            pixels[i] = ColorUtils.valueToColor(heatmapData[i])
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        bitmap.asImageBitmap()
    }

    // 文本测量器 (用于画坐标数值)
    val textMeasurer = rememberTextMeasurer()

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
            .border(2.dp, Color.Gray, RoundedCornerShape(8.dp))
    ) {
        // --- 第一层：热力图底图 ---
        Image(
            bitmap = imageBitmap,
            contentDescription = "Heatmap",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
            filterQuality = FilterQuality.High // 双线性插值，平滑化
        )

        // --- 第二层：坐标轴与网格覆盖层 ---
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // 定义物理范围：-1.0m 到 +1.0m
            // 步长：0.5m
            val steps = listOf(-1.0f, -0.5f, 0.0f, 0.5f, 1.0f)

            // 样式定义
            val gridColor = Color.White.copy(alpha = 0.5f) // 半透明白线
            val centerColor = Color.White // 中心线亮白色
            val textStyle = TextStyle(
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                background = Color.Black.copy(alpha = 0.4f) // 文字加个黑底背景，防止看不清
            )

            // 虚线效果
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)

            // --- 绘制网格与文字 ---
            steps.forEach { value ->
                // 归一化位置 (0.0 ~ 1.0)
                // 物理坐标 value: -1 ~ 1
                // 屏幕比例 ratio: (value - (-1)) / 2 = (value + 1) / 2
                val ratio = (value + 1f) / 2f

                val screenX = w * ratio
                // 注意：屏幕Y轴是向下的，而物理坐标Y轴通常向上。
                // 如果我们定义上方为 +1m，下方为 -1m (声学相机视角)
                // 则 screenY = h * (1 - ratio)
                val screenY = h * (1f - ratio)

                // 1. 画竖线 (X轴刻度)
                drawLine(
                    color = if (value == 0f) centerColor else gridColor,
                    start = Offset(screenX, 0f),
                    end = Offset(screenX, h),
                    strokeWidth = if (value == 0f) 2f else 1f,
                    pathEffect = if (value == 0f) null else dashEffect
                )

                // X轴文字 (写在底部)
                if (value != 1.0f) { // 避免最右边溢出
                    val text = "${value}m"
                    drawText(
                        textMeasurer = textMeasurer,
                        text = text,
                        style = textStyle,
                        topLeft = Offset(screenX + 4f, h - 40f) // 稍微偏移一点
                    )
                }

                // 2. 画横线 (Y轴刻度)
                drawLine(
                    color = if (value == 0f) centerColor else gridColor,
                    start = Offset(0f, screenY),
                    end = Offset(w, screenY),
                    strokeWidth = if (value == 0f) 2f else 1f,
                    pathEffect = if (value == 0f) null else dashEffect
                )

                // Y轴文字 (写在左侧)
                if (value != -1.0f) { // 避免最底下重叠
                    val text = "${value}m"
                    drawText(
                        textMeasurer = textMeasurer,
                        text = text,
                        style = textStyle,
                        topLeft = Offset(10f, screenY + 4f)
                    )
                }
            }

//            // --- 绘制左上角提示 ---
//            drawText(
//                textMeasurer = textMeasurer,
//                text = "Scan Plane: 2m x 2m (Z=1m)",
//                style = TextStyle(color = Color.Yellow, fontSize = 10.sp),
//                topLeft = Offset(10f, 10f)
//            )
        }
    }
}