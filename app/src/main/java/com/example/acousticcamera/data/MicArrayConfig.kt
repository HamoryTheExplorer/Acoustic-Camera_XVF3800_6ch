package com.example.acousticcamera.data

import kotlin.math.sqrt

/**
 * 定义麦克风阵列坐标
 *
 * 4 通道方形阵列，边长 63.6mm，对角线 90mm。
 * 适配 XMOS XVF3800 4 路 PDM 麦克风。
 *
 * 坐标方向约定（面朝阵列正面，z 轴指向声源方向）：
 *   X 轴 → 右为正，Y 轴 → 上为正，Z 轴 → 声源方向（阵列平面为 Z=0）
 *
 * ⚠️ 若实际板载麦克风排列顺序不同，调整本列表中麦克风顺序即可匹配通道。
 */

// 简单的 3D 点类
data class Point3D(val x: Float, val y: Float, val z: Float)

object MicArrayConfig {

    /** 方形阵列半边长 (m) — 全边长 63.6mm，对角线 90mm */
    const val ARRAY_HALF_SIDE = 0.0318f

    /** 最大麦克风间距 = 对角线 (m) — 用于互相关 maxLag 等计算 */
    const val ARRAY_MAX_SPACING = 0.09f

    val mics: List<Point3D> = run {
        val a = ARRAY_HALF_SIDE
        listOf(
            // 按 USB 通道映射排列（Phase 6 RMS 实测确认）：
            // ch0 = RMS#1 = 右下，ch1 = RMS#2 = 左下
            // ch2 = RMS#3 = 左上，ch3 = RMS#4 = 右上
            Point3D( +a,  -a,  0f),  // Mic 0 (ch0): 右下
            Point3D( -a,  -a,  0f),  // Mic 1 (ch1): 左下
            Point3D( -a,  +a,  0f),  // Mic 2 (ch2): 左上
            Point3D( +a,  +a,  0f),  // Mic 3 (ch3): 右上
        )
    }

    // 计算两点距离的辅助函数
    fun distance(p1: Point3D, p2: Point3D): Float {
        val dx = p1.x - p2.x
        val dy = p1.y - p2.y
        val dz = p1.z - p2.z
        return sqrt(dx*dx + dy*dy + dz*dz)
    }
}
