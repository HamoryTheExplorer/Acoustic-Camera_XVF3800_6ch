package com.example.acousticcamera.data

import kotlin.math.sqrt

/**
 * 定义麦克风阵列坐标
 * 模拟一个 8x8 的矩形阵列（共64个麦克风），间距 4cm
 */

// 简单的 3D 点类
data class Point3D(val x: Float, val y: Float, val z: Float)

object MicArrayConfig {
    // 定义 8x8 阵列，间距 0.04米 (4cm)
    // 阵列中心在 (0,0,0)
    val mics: List<Point3D> = run {
        val list = mutableListOf<Point3D>()
        val rows = 8
        val cols = 8
        val spacing = 0.04f

        // 计算起始偏移，让中心在 0,0
        val startX = -(cols - 1) * spacing / 2
        val startY = -(rows - 1) * spacing / 2

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                list.add(
                    Point3D(
                        x = startX + c * spacing,
                        y = startY + r * spacing,
                        z = 0f // 阵列都在 Z=0 平面上
                    )
                )
            }
        }
        list
    }

    // 计算两点距离的辅助函数
    fun distance(p1: Point3D, p2: Point3D): Float {
        val dx = p1.x - p2.x
        val dy = p1.y - p2.y
        val dz = p1.z - p2.z
        return sqrt(dx*dx + dy*dy + dz*dz)
    }
}