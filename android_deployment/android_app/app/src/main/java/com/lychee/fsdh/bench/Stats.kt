package com.lychee.fsdh.bench

import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Descriptive statistics for the deployment measurements.
 *
 * These run on the RAW per-frame list after a session ends. The numbers on the
 * live screen are a 20-frame rolling average and exist only to keep the display
 * from flickering; they are never the reported figures.
 *
 * P95 is the NEAREST-RANK percentile: sort, then take element
 * `ceil(0.95 * n) - 1`. The alternative (linear interpolation between order
 * statistics) gives a different number on a sample this size, and a value that
 * may never have been measured. Nearest-rank always returns a latency that
 * actually occurred, which is what "were there occasional slow frames" is
 * asking. Whichever is used has to be named in the paper.
 */
object Stats {

    data class Summary(
        val n: Int,
        val mean: Double,
        val sd: Double,
        val median: Double,
        val p95: Double,
        val min: Double,
        val max: Double,
    )

    /** [xs] must be non-empty; callers decide what to do with a short session. */
    fun summarise(xs: List<Double>): Summary {
        require(xs.isNotEmpty()) { "summarise() needs at least one sample" }
        val s = xs.sorted()
        val m = s.average()
        return Summary(
            n = s.size,
            mean = m,
            sd = sd(s, m),
            median = median(s),
            p95 = s[(ceil(0.95 * s.size).toInt() - 1).coerceIn(0, s.size - 1)],
            min = s.first(),
            max = s.last(),
        )
    }

    /** Sample standard deviation (n-1). NaN below two samples, as elsewhere. */
    fun sd(xs: List<Double>, mean: Double = xs.average()): Double {
        if (xs.size < 2) return Double.NaN
        return sqrt(xs.sumOf { (it - mean) * (it - mean) } / (xs.size - 1))
    }

    /** [sorted] must already be sorted. Mean of the two middle values when even. */
    private fun median(sorted: List<Double>): Double {
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }
}
