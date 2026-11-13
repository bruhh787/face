package com.facesort.app

import android.content.Context
import com.facesort.app.cluster.Defaults

/** How densely videos are sampled. */
enum class ScanMode(val label: String, val stepMs: Long, val maxFrames: Int, val exactSeek: Boolean) {
    FAST("Fast", 4000, 100, false),
    BALANCED("Balanced", 2000, 200, false),
    THOROUGH("Thorough", 1000, 400, true);

    companion object {
        /** Frame timestamps (ms): one every stepMs, spread out further for long videos so at most maxFrames. */
        fun sampleTimes(durationMs: Long, mode: ScanMode): List<Long> {
            if (durationMs <= 0) return listOf(0L)
            val step = maxOf(mode.stepMs, (durationMs + mode.maxFrames - 1) / mode.maxFrames)
            if (durationMs <= step) return listOf(durationMs / 2)
            val out = ArrayList<Long>()
            var t = step / 2
            while (t < durationMs && out.size < mode.maxFrames) { out += t; t += step }
            return out
        }
    }
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("facesort", Context.MODE_PRIVATE)

    /** Cosine similarity (ArcFace) for faces to be grouped together when clustering unknowns. */
    var clusterThreshold: Float
        get() = sp.getFloat("clusterThrArc", Defaults.CLUSTER_THRESHOLD)
        set(v) = sp.edit().putFloat("clusterThrArc", v).apply()

    /** Cosine similarity (ArcFace) needed to auto-match a face to an already named person. */
    var matchThreshold: Float
        get() = sp.getFloat("matchThrArc", Defaults.MATCH_THRESHOLD)
        set(v) = sp.edit().putFloat("matchThrArc", v).apply()

    /** Share of a video's faces the dominant person must have, else the video goes to Review. */
    var minShare: Float
        get() = sp.getFloat("minShare", Defaults.MIN_SHARE)
        set(v) = sp.edit().putFloat("minShare", v).apply()

    var scanMode: ScanMode
        get() = runCatching { ScanMode.valueOf(sp.getString("scanMode", null) ?: "") }.getOrDefault(ScanMode.BALANCED)
        set(v) = sp.edit().putString("scanMode", v.name).apply()

    /** Selected source folders (MediaStore RELATIVE_PATH values). Empty = all videos on the phone. */
    var sourceFolders: Set<String>
        get() = sp.getStringSet("folders", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("folders", v).apply()

    var moveReviewToo: Boolean
        get() = sp.getBoolean("moveReview", false)
        set(v) = sp.edit().putBoolean("moveReview", v).apply()

    /** Set when the DB was wiped because the face model changed; UI shows a one-time notice. */
    var modelUpgradeNotice: Boolean
        get() = sp.getBoolean("modelUpgradeNotice", false)
        set(v) = sp.edit().putBoolean("modelUpgradeNotice", v).apply()

    companion object {
        const val ROOT_FOLDER = "Movies/FaceSort/"
        const val REVIEW_FOLDER = "Movies/FaceSort/_Review/"
    }
}
