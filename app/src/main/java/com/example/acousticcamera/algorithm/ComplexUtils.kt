package com.example.acousticcamera.algorithm

import kotlin.math.cos
import kotlin.math.sin

/**
 * 简单的复数类
 */

data class Complex(val re: Double, val im: Double) {
    operator fun plus(other: Complex) = Complex(re + other.re, im + other.im)
    operator fun minus(other: Complex) = Complex(re - other.re, im - other.im)
    operator fun times(other: Complex) = Complex(re * other.re - im * other.im, re * other.im + im * other.re)
    operator fun times(scalar: Double) = Complex(re * scalar, im * scalar)
    operator fun div(scalar: Double) = Complex(re / scalar, im / scalar)

    // 共轭
    fun conj() = Complex(re, -im)

    // 模的平方 (能量)
    fun absSq() = re * re + im * im
}

// 欧拉公式：exp(ix) = cos(x) + i*sin(x)
fun expIj(x: Double): Complex = Complex(cos(x), sin(x))