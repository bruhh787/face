package com.facesort.app.cluster

import kotlin.math.sqrt

/** Pure-Kotlin vector helpers (no Android deps, unit-testable). */
object VecMath {
    fun normalize(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += x * x
        val n = sqrt(s).toFloat()
        if (n < 1e-12f) return v.copyOf()
        return FloatArray(v.size) { v[it] / n }
    }

    fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /** Normalized mean of (already normalized) vectors. */
    fun centroid(vs: List<FloatArray>): FloatArray {
        require(vs.isNotEmpty())
        val out = FloatArray(vs[0].size)
        for (v in vs) for (i in out.indices) out[i] += v[i]
        return normalize(out)
    }
}

/**
 * Face clustering by cosine similarity.
 *
 * 1. Leader pass: each face joins the most similar running centroid if sim >= threshold, else starts a cluster.
 * 2. Agglomerative pass on the clusters (centroid / average-linkage approximation): repeatedly merge the
 *    most similar pair of clusters while their centroid similarity >= threshold.
 * 3. Refinement: reassign each face to its nearest centroid (k-means style) a few times.
 * 4. Clusters smaller than [minClusterSize] are treated as noise (index -1), DBSCAN style.
 *
 * Embeddings must be L2-normalized.
 */
object Clustering {

    /** @return label per input embedding: cluster index 0..k-1, or -1 for noise. */
    fun cluster(
        embs: List<FloatArray>,
        threshold: Float,
        minClusterSize: Int = 2,
        refineIters: Int = 2,
    ): IntArray {
        val n = embs.size
        val labels = IntArray(n) { -1 }
        if (n == 0) return labels
        val dim = embs[0].size

        // --- 1. leader pass
        val sums = ArrayList<FloatArray>()
        val cents = ArrayList<FloatArray>()
        val counts = ArrayList<Int>()
        for (i in 0 until n) {
            val e = embs[i]
            var best = -1
            var bestSim = -2f
            for (c in cents.indices) {
                val s = VecMath.dot(e, cents[c])
                if (s > bestSim) { bestSim = s; best = c }
            }
            if (best >= 0 && bestSim >= threshold) {
                val sum = sums[best]
                for (d in 0 until dim) sum[d] += e[d]
                cents[best] = VecMath.normalize(sum)
                counts[best] = counts[best] + 1
                labels[i] = best
            } else {
                sums.add(e.copyOf()); cents.add(e.copyOf()); counts.add(1)
                labels[i] = cents.size - 1
            }
        }

        // --- 2. agglomerative merge of clusters
        var k = cents.size
        val alive = BooleanArray(k) { true }
        val sim = Array(k) { FloatArray(k) }
        for (a in 0 until k) for (b in a + 1 until k) {
            val s = VecMath.dot(cents[a], cents[b]); sim[a][b] = s; sim[b][a] = s
        }
        val parent = IntArray(k) { it }
        while (true) {
            var ba = -1; var bb = -1; var bs = -2f
            for (a in 0 until k) {
                if (!alive[a]) continue
                val row = sim[a]
                for (b in a + 1 until k) {
                    if (alive[b] && row[b] > bs) { bs = row[b]; ba = a; bb = b }
                }
            }
            if (ba < 0 || bs < threshold) break
            // merge bb into ba
            val sa = sums[ba]; val sb = sums[bb]
            for (d in 0 until dim) sa[d] += sb[d]
            cents[ba] = VecMath.normalize(sa)
            counts[ba] = counts[ba] + counts[bb]
            alive[bb] = false
            parent[bb] = ba
            for (c in 0 until k) if (alive[c] && c != ba) {
                val s = VecMath.dot(cents[ba], cents[c]); sim[ba][c] = s; sim[c][ba] = s
            }
        }
        fun root(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; return r }
        for (i in 0 until n) labels[i] = root(labels[i])

        // compact
        var aliveIdx = (0 until k).filter { alive[it] }
        var centList = aliveIdx.map { cents[it] }
        val remap = HashMap<Int, Int>().apply { aliveIdx.forEachIndexed { j, c -> put(c, j) } }
        for (i in 0 until n) labels[i] = remap[labels[i]]!!
        k = centList.size

        // --- 3. refinement
        repeat(refineIters) {
            for (i in 0 until n) {
                var best = labels[i]; var bestSim = VecMath.dot(embs[i], centList[best])
                for (c in 0 until k) {
                    val s = VecMath.dot(embs[i], centList[c])
                    if (s > bestSim) { bestSim = s; best = c }
                }
                labels[i] = best
            }
            val newSums = Array(k) { FloatArray(dim) }
            val nc = IntArray(k)
            for (i in 0 until n) { val l = labels[i]; nc[l]++; val s = newSums[l]; val e = embs[i]; for (d in 0 until dim) s[d] += e[d] }
            centList = (0 until k).map { if (nc[it] == 0) centList[it] else VecMath.normalize(newSums[it]) }
        }

        // --- 4. noise + compact
        val sizes = IntArray(k)
        for (l in labels) sizes[l]++
        val keep = (0 until k).filter { sizes[it] >= minClusterSize }
        val finalMap = HashMap<Int, Int>().apply { keep.forEachIndexed { j, c -> put(c, j) } }
        for (i in 0 until n) labels[i] = finalMap[labels[i]] ?: -1
        return labels
    }

    data class Match(val personId: Long, val score: Float)

    /**
     * Match an embedding against known people. Score per person = mean of the top-3 similarities to that
     * person's exemplar embeddings. Returns null if below [threshold] or too close to the runner-up.
     */
    fun matchKnown(
        emb: FloatArray,
        people: Map<Long, List<FloatArray>>,
        threshold: Float,
        margin: Float = 0.04f,
    ): Match? {
        var bestId = -1L; var best = -2f; var second = -2f
        for ((id, exemplars) in people) {
            if (exemplars.isEmpty()) continue
            val sims = exemplars.map { VecMath.dot(emb, it) }.sortedDescending()
            val top = sims.take(3)
            val score = top.sum() / top.size
            if (score > best) { second = best; best = score; bestId = id } else if (score > second) second = score
        }
        if (bestId < 0 || best < threshold) return null
        if (second > -2f && best - second < margin) return null
        return Match(bestId, best)
    }

    data class VideoAssignment(val groupId: Long?, val confidence: Float, val review: Boolean)

    /**
     * Decide which person a video belongs to from the group of each detected face (null = unknown/noise).
     * [weights] = how many sampled frames each stored face stands for (faces are condensed per video);
     * defaults to 1 each. The dominant group (largest weight) wins; the video goes to Review if the evidence is
     * thin or ambiguous.
     */
    fun assignVideo(
        faceGroups: List<Long?>,
        weights: List<Int>? = null,
        minFaces: Int = 2,
        minShare: Float = 0.5f,
        ambiguityRatio: Float = 0.75f,
    ): VideoAssignment {
        if (faceGroups.isEmpty()) return VideoAssignment(null, 0f, false)
        val w = weights ?: List(faceGroups.size) { 1 }
        require(w.size == faceGroups.size)
        val total = w.sum().coerceAtLeast(1)
        val counts = HashMap<Long, Int>()
        for (i in faceGroups.indices) faceGroups[i]?.let { counts[it] = (counts[it] ?: 0) + w[i] }
        if (counts.isEmpty()) return VideoAssignment(null, 0f, true)
        val sorted = counts.entries.sortedByDescending { it.value }
        val top = sorted[0]
        val second = sorted.getOrNull(1)?.value ?: 0
        val share = top.value.toFloat() / total
        val review = top.value < minFaces || share < minShare || second >= ambiguityRatio * top.value
        return VideoAssignment(top.key, share, review)
    }

    /**
     * Condense one video's faces: cluster them locally, and per local person keep at most [keep] best-quality
     * faces. Returns (index of kept face, weight) where the weights of a local cluster sum to its size, so
     * frame counts for dominance are preserved while storage stays small.
     */
    fun condense(embs: List<FloatArray>, quality: List<Float>, threshold: Float, keep: Int): List<Pair<Int, Int>> {
        if (embs.isEmpty()) return emptyList()
        val labels = cluster(embs, threshold, minClusterSize = 1)
        val out = ArrayList<Pair<Int, Int>>()
        for ((_, idxs) in embs.indices.groupBy { labels[it] }) {
            val best = idxs.sortedByDescending { quality[it] }.take(keep)
            val rest = idxs.size - best.size
            best.forEachIndexed { j, i -> out += i to (if (j == 0) 1 + rest else 1) }
        }
        return out
    }
}
