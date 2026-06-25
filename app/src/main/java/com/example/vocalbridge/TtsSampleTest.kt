package com.example.vocalbridge

import android.util.Log

object TtsSampleTest {
    fun printSamples(samples: FloatArray) {
        val first5 = samples.take(5).joinToString(", ")
        val min = samples.minOrNull() ?: 0f
        val max = samples.maxOrNull() ?: 0f
        Log.d("TtsSampleTest", "Samples: $first5 | Min: $min, Max: $max")
    }
}
