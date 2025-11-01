//com/example/mobilgaleri/util/ThresholdCalibrator.kt
package com.example.mobilgaleri.util

data class PairScore(val cos: Float, val same: Boolean)

object ThresholdCalibrator {
    /** 0.30..0.80 aralığında en iyi F1’i veren eşiği döndürür. */
    fun bestThresholdF1(pairs: List<PairScore>, step: Float = 0.01f,
                        start: Float = 0.30f, end: Float = 0.80f): Float {
        var bestT = start; var bestF1 = -1f
        var t = start
        while (t <= end + 1e-6f) {
            var tp=0; var fp=0; var fn=0
            for (p in pairs) {
                val predSame = p.cos >= t
                if (predSame && p.same) tp++ else if (predSame && !p.same) fp++ else if (!predSame && p.same) fn++
            }
            val precision = if (tp+fp==0) 0f else tp.toFloat()/(tp+fp)
            val recall    = if (tp+fn==0) 0f else tp.toFloat()/(tp+fn)
            val f1 = if (precision+recall==0f) 0f else 2*precision*recall/(precision+recall)
            if (f1 > bestF1) { bestF1 = f1; bestT = t }
            t += step
        }
        return bestT
    }
}
