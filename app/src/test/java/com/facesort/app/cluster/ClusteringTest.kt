package com.facesort.app.cluster

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ClusteringTest {
    private val dim = 128
    private val rnd = Random(42)

    private fun randomUnit(): FloatArray = VecMath.normalize(FloatArray(dim) { rnd.nextFloat() * 2 - 1 })
    private fun jitter(base: FloatArray, noise: Float) =
        VecMath.normalize(FloatArray(dim) { base[it] + (rnd.nextFloat() * 2 - 1) * noise })

    @Test fun threeIdentitiesAreSeparated() {
        val ids = List(3) { randomUnit() }
        val embs = ArrayList<FloatArray>(); val truth = ArrayList<Int>()
        repeat(60) { i -> val p = i % 3; embs.add(jitter(ids[p], 0.08f)); truth.add(p) }
        val labels = Clustering.cluster(embs, threshold = 0.6f)
        assertEquals(3, labels.filter { it >= 0 }.toSet().size)
        // every true identity maps to exactly one label
        for (p in 0 until 3) {
            val ls = embs.indices.filter { truth[it] == p }.map { labels[it] }.toSet()
            assertEquals(1, ls.size)
        }
    }

    @Test fun singletonsBecomeNoise() {
        val a = randomUnit()
        val embs = listOf(jitter(a, 0.05f), jitter(a, 0.05f), jitter(a, 0.05f), randomUnit())
        val labels = Clustering.cluster(embs, threshold = 0.6f, minClusterSize = 2)
        assertEquals(-1, labels[3])
        assertTrue(labels[0] >= 0 && labels[0] == labels[1] && labels[1] == labels[2])
    }

    @Test fun emptyInput() { assertEquals(0, Clustering.cluster(emptyList(), 0.5f).size) }

    @Test fun matchKnownPicksRightPerson() {
        val p1 = randomUnit(); val p2 = randomUnit()
        val people = mapOf(1L to List(5) { jitter(p1, 0.05f) }, 2L to List(5) { jitter(p2, 0.05f) })
        assertEquals(2L, Clustering.matchKnown(jitter(p2, 0.05f), people, 0.6f)?.personId)
        assertNull(Clustering.matchKnown(randomUnit(), people, 0.6f))
    }

    @Test fun assignVideoDominantAndReview() {
        val a = Clustering.assignVideo(listOf(1L, 1L, 1L, 2L, null))
        assertEquals(1L, a.groupId); assertFalse(a.review)
        val amb = Clustering.assignVideo(listOf(1L, 1L, 2L, 2L))
        assertTrue(amb.review)
        val thin = Clustering.assignVideo(listOf(1L))
        assertTrue(thin.review)
        val none = Clustering.assignVideo(emptyList())
        assertNull(none.groupId); assertFalse(none.review)
        val allUnknown = Clustering.assignVideo(listOf(null, null))
        assertTrue(allUnknown.review)
    }

    @Test fun weightedAssignment() {
        // one stored face of person 1 standing for 9 frames beats three single faces of person 2
        val a = Clustering.assignVideo(listOf(1L, 2L, 2L, 2L), listOf(9, 1, 1, 1))
        assertEquals(1L, a.groupId); assertFalse(a.review)
        assertEquals(0.75f, a.confidence, 1e-6f)
    }

    @Test fun condenseKeepsBestAndPreservesCounts() {
        val p1 = randomUnit(); val p2 = randomUnit()
        val embs = List(10) { jitter(p1, 0.05f) } + List(4) { jitter(p2, 0.05f) }
        val q = List(embs.size) { it.toFloat() }
        val kept = Clustering.condense(embs, q, threshold = 0.6f, keep = 3)
        assertEquals(6, kept.size)                       // 3 per local person
        assertEquals(14, kept.sumOf { it.second })       // weights preserve frame counts
        assertTrue(kept.any { it.first == 9 } && kept.any { it.first == 13 }) // highest quality kept
    }
}
