package com.example.acousticcamera.ui

import android.graphics.Color as AndroidColor

/**
 * 颜色映射工具
 * 把数值转成颜色
 */
object ColorUtils {

    /**
     * 将 0.0 ~ 1.0 的数值映射为热力图颜色 (Jet Colormap 风格)
     * 0.0 -> 蓝色 (Blue)
     * 0.5 -> 绿色 (Green)
     * 1.0 -> 红色 (Red)
     *
     * @param overlayMode true 时启用叠加透明度：能量越高越不透明，冷区接近全透明，
     *                    摄像头画面透过冷区显示出来。
     */
    fun valueToColor(value: Float, overlayMode: Boolean = false): Int {
        val v = value.coerceIn(0f, 1f)

        val r: Int
        val g: Int
        val b: Int

        if (v < 0.5f) {
            val ratio = v * 2
            r = 0
            g = (255 * ratio).toInt()
            b = (255 * (1 - ratio)).toInt()
        } else {
            val ratio = (v - 0.5f) * 2
            r = (255 * ratio).toInt()
            g = (255 * (1 - ratio)).toInt()
            b = 0
        }

        // 叠加模式：alpha 随能量线性增大，让高声压热点不透明、低声压区透明
        val alpha = if (overlayMode) (v * 220f).toInt() else 255
        return AndroidColor.argb(alpha, r, g, b)
    }
}