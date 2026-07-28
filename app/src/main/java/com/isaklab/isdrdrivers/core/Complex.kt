package com.isaklab.isdrdrivers.core

/**
 * Immutable representation of a complex number (real + imaginary)
 * used for baseband DSP calculations.
 *
 * Note: To comply with real-time requirements, GC allocations are forbidden 
 * on the streaming path. Do not instantiate these objects inside tight 
 * processing loops (e.g., sample-by-sample processing).
 */
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
