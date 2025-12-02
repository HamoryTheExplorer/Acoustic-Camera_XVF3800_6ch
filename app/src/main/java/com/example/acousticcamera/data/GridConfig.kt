package com.example.acousticcamera.data

/**
 * 定义扫描平面
 * 定义一个虚拟的“屏幕”，放在距离阵列前方 1 米处
 */

object GridConfig {
    // 定义热力图的分辨率 (50x50 像素)
    // 越高越清晰，但计算量是平方级增长
    const val GRID_SIZE = 50

    // 扫描平面的物理尺寸 (比如 2米 x 2米)
    const val PLANE_SIZE = 2.0f

    // 扫描平面距离麦克风阵列的距离 (Z轴深度)
    const val Z_DISTANCE = 1.0f

    // 获取第 (x, y) 个格子的三维坐标
    // gridX, gridY 范围是 0 until GRID_SIZE
    fun getPoint3D(gridX: Int, gridY: Int): Point3D {
        // 将 0..49 映射到 -1.0米..+1.0米
        val step = PLANE_SIZE / GRID_SIZE
        val start = -PLANE_SIZE / 2

        val px = start + gridX * step
        val py = start + gridY * step
        // Z轴固定在 1.0米处
        return Point3D(px, py, Z_DISTANCE)
    }
}