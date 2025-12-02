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
     */
    fun valueToColor(value: Float): Int {
        // 限制范围在 0~1 之间
        val v = value.coerceIn(0f, 1f)

        val r: Int
        val g: Int
        val b: Int

        // 简单的线性插值算法
        if (v < 0.5f) {
            // 0.0 ~ 0.5: 蓝 -> 绿
            // 比例 ratio: 0 ~ 1
            val ratio = v * 2
            r = 0
            g = (255 * ratio).toInt()
            b = (255 * (1 - ratio)).toInt()
        } else {
            // 0.5 ~ 1.0: 绿 -> 红
            val ratio = (v - 0.5f) * 2
            r = (255 * ratio).toInt()
            g = (255 * (1 - ratio)).toInt()
            b = 0
        }

        // 返回 ARGB 整数
        return AndroidColor.rgb(r, g, b)
    }
}