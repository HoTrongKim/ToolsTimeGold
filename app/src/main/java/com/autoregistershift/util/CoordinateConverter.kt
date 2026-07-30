package com.autoregistershift.util

import kotlin.math.roundToInt

object CoordinateConverter {
    fun toReal(ratio: Float, size: Int): Int =
        (ratio.coerceIn(0f, 1f) * size).roundToInt()

    fun toRatio(coordinate: Float, size: Int): Float =
        if (size <= 0) 0f else (coordinate / size).coerceIn(0f, 1f)
}
