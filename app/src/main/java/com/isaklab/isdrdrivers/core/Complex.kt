package com.isaklab.isdrdrivers.core

data class Complex(val real: Double, val imaginary: Double) {

    val magnitude: Double
        get() = kotlin.math.sqrt(real * real + imaginary * imaginary)

    val phase: Double
        get() = kotlin.math.atan2(imaginary, real)

    operator fun plus(other: Complex) = Complex(real + other.real, imaginary + other.imaginary)
    operator fun minus(other: Complex) = Complex(real - other.real, imaginary - other.imaginary)
    operator fun times(other: Complex) = Complex(
        real * other.real - imaginary * other.imaginary,
        real * other.imaginary + imaginary * other.real
    )

    operator fun times(scalar: Double) = Complex(real * scalar, imaginary * scalar)

    companion object {
        val ZERO = Complex(0.0, 0.0)
    }
}
