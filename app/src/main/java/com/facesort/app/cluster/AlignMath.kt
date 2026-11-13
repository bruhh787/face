package com.facesort.app.cluster

/**
 * Pure maths for face alignment (no Android deps, unit-tested).
 *
 * A 2-D similarity transform (rotation + uniform scale + translation, no reflection/shear) is stored as
 * [a, b, tx, ty] meaning   x' = a*x - b*y + tx ,   y' = b*x + a*y + ty
 * (scale = sqrt(a^2 + b^2), angle = atan2(b, a)).
 */
object AlignMath {
    /** InsightFace ArcFace 112x112 template, image-left first: eye, eye, mouth corner, mouth corner. */
    val ARCFACE_EYES_MOUTH = floatArrayOf(
        38.2946f, 51.6963f, 73.5318f, 51.5014f,
        41.5493f, 92.3655f, 70.7299f, 92.2041f,
    )
    val ARCFACE_EYES = ARCFACE_EYES_MOUTH.copyOfRange(0, 4)

    /**
     * Least-squares similarity transform mapping src points onto dst points (Umeyama without reflection).
     * Both arrays are interleaved x,y with the same number (>= 2) of points. Returns null if degenerate.
     */
    fun similarity(src: FloatArray, dst: FloatArray): FloatArray? {
        require(src.size == dst.size && src.size >= 4 && src.size % 2 == 0)
        val n = src.size / 2
        var pmx = 0.0; var pmy = 0.0; var qmx = 0.0; var qmy = 0.0
        for (i in 0 until n) { pmx += src[2 * i]; pmy += src[2 * i + 1]; qmx += dst[2 * i]; qmy += dst[2 * i + 1] }
        pmx /= n; pmy /= n; qmx /= n; qmy /= n
        var num1 = 0.0; var num2 = 0.0; var den = 0.0
        for (i in 0 until n) {
            val px = src[2 * i] - pmx; val py = src[2 * i + 1] - pmy
            val qx = dst[2 * i] - qmx; val qy = dst[2 * i + 1] - qmy
            num1 += px * qx + py * qy
            num2 += px * qy - py * qx
            den += px * px + py * py
        }
        if (den < 1e-6) return null
        val a = num1 / den
        val b = num2 / den
        val tx = qmx - (a * pmx - b * pmy)
        val ty = qmy - (b * pmx + a * pmy)
        return floatArrayOf(a.toFloat(), b.toFloat(), tx.toFloat(), ty.toFloat())
    }

    fun apply(t: FloatArray, x: Float, y: Float): Pair<Float, Float> =
        Pair(t[0] * x - t[1] * y + t[2], t[1] * x + t[0] * y + t[3])

    /** Row-major 3x3 values suitable for android.graphics.Matrix.setValues(). */
    fun toMatrixValues(t: FloatArray): FloatArray =
        floatArrayOf(t[0], -t[1], t[2], t[1], t[0], t[3], 0f, 0f, 1f)

    /** RMS distance (in template pixels) between transformed src points and dst: an alignment-quality check. */
    fun residual(t: FloatArray, src: FloatArray, dst: FloatArray): Float {
        var s = 0.0
        val n = src.size / 2
        for (i in 0 until n) {
            val (x, y) = apply(t, src[2 * i], src[2 * i + 1])
            val dx = x - dst[2 * i]; val dy = y - dst[2 * i + 1]
            s += dx * dx + dy * dy
        }
        return kotlin.math.sqrt(s / n).toFloat()
    }
}

/** Default thresholds for ArcFace (InsightFace w600k_r50) cosine similarities. See README for rationale. */
object Defaults {
    const val CLUSTER_THRESHOLD = 0.48f
    const val MATCH_THRESHOLD = 0.42f
    const val MIN_SHARE = 0.45f
    const val SLIDER_MIN = 0.25f
    const val SLIDER_MAX = 0.75f
}

/** Pure part of the duplicate-frame check (unit-tested). */
object FrameDiff {
    fun meanAbsDiff(a: IntArray, b: IntArray): Float {
        var d = 0L
        for (i in a.indices) d += kotlin.math.abs(a[i] - b[i])
        return d.toFloat() / a.size
    }
    fun nearlySame(a: IntArray, b: IntArray, maxMeanDiff: Float = 1f) = meanAbsDiff(a, b) <= maxMeanDiff
}
