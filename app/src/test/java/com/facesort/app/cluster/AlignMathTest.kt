package com.facesort.app.cluster

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class AlignMathTest {
    private val tpl = AlignMath.ARCFACE_EYES_MOUTH

    /** Build "detected" landmarks by applying the inverse of a known face pose to the template. */
    private fun warp(points: FloatArray, scale: Float, angleDeg: Float, tx: Float, ty: Float): FloatArray {
        val c = cos(Math.toRadians(angleDeg.toDouble())).toFloat() * scale
        val s = sin(Math.toRadians(angleDeg.toDouble())).toFloat() * scale
        return FloatArray(points.size) { i ->
            val x = points[i - i % 2]; val y = points[i - i % 2 + 1]
            if (i % 2 == 0) c * x - s * y + tx else s * x + c * y + ty
        }
    }

    @Test fun identityOnTemplate() {
        val t = AlignMath.similarity(tpl, tpl)!!
        assertEquals(1f, t[0], 1e-5f); assertEquals(0f, t[1], 1e-5f)
        assertEquals(0f, t[2], 1e-3f); assertEquals(0f, t[3], 1e-3f)
    }

    @Test fun recoversRotatedScaledFace() {
        // a face 4x bigger than the template, rolled 25 degrees, somewhere in a 1280px frame
        val detected = warp(tpl, 4f, 25f, 600f, 300f)
        val t = AlignMath.similarity(detected, tpl)!!
        assertTrue(AlignMath.residual(t, detected, tpl) < 1e-2f)
        assertEquals(0.25f, sqrt(t[0] * t[0] + t[1] * t[1]), 1e-4f)              // scale back down 4x
        assertEquals(-25.0, Math.toDegrees(atan2(t[1], t[0]).toDouble()), 1e-3) // undo the roll
    }

    @Test fun twoPointEyesOnlyIsExact() {
        val eyes = warp(AlignMath.ARCFACE_EYES, 2.5f, -10f, 100f, 40f)
        val t = AlignMath.similarity(eyes, AlignMath.ARCFACE_EYES)!!
        assertTrue(AlignMath.residual(t, eyes, AlignMath.ARCFACE_EYES) < 1e-2f)
    }

    @Test fun noisyLandmarksStillClose() {
        val d = warp(tpl, 3f, 8f, 50f, 70f)
        val noisy = FloatArray(d.size) { d[it] + if (it % 3 == 0) 2f else -1.5f }
        val t = AlignMath.similarity(noisy, tpl)!!
        assertTrue(AlignMath.residual(t, noisy, tpl) < 1.5f) // < 1.5 template px
    }

    @Test fun degenerateReturnsNull() {
        assertNull(AlignMath.similarity(floatArrayOf(5f, 5f, 5f, 5f), AlignMath.ARCFACE_EYES))
    }

    @Test fun matrixValuesLayout() {
        val m = AlignMath.toMatrixValues(floatArrayOf(0.5f, 0.2f, 3f, 4f))
        assertArrayEquals(floatArrayOf(0.5f, -0.2f, 3f, 0.2f, 0.5f, 4f, 0f, 0f, 1f), m, 0f)
    }

    @Test fun defaultsAreSane() {
        // matching against many exemplars (mean of top-3) is more robust than centroid clustering,
        // so it may be a little looser; both must sit inside the Settings slider range.
        assertTrue(Defaults.MATCH_THRESHOLD < Defaults.CLUSTER_THRESHOLD)
        for (v in listOf(Defaults.MATCH_THRESHOLD, Defaults.CLUSTER_THRESHOLD))
            assertTrue(v in Defaults.SLIDER_MIN..Defaults.SLIDER_MAX)
        assertTrue(Defaults.CLUSTER_THRESHOLD in 0.45f..0.50f)
        assertTrue(Defaults.MATCH_THRESHOLD in 0.40f..0.45f)
    }
}
