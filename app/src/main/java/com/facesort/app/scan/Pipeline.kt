package com.facesort.app.scan

import android.content.Context
import android.net.Uri
import com.facesort.app.Prefs
import com.facesort.app.cluster.Clustering
import com.facesort.app.data.*
import com.facesort.app.media.MediaRepo
import com.facesort.app.ml.FaceEmbedder
import com.facesort.app.ml.FaceExtractor
import androidx.room.withTransaction
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import com.facesort.app.ml.ExtractedFace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import java.io.File

/** All grouping logic: scanning, matching to known people, clustering unknowns, assigning videos. */
class Pipeline(private val context: Context) {
    private val db = AppDatabase.get(context)
    private val dao = db.dao()
    private val prefs = Prefs(context)
    private val media = MediaRepo(context)

    data class Progress(val phase: String, val done: Int, val total: Int)

    suspend fun scan(onProgress: suspend (Progress) -> Unit) {
        onProgress(Progress("Listing videos", 0, 0))
        val found = media.queryVideos(prefs.sourceFolders)
        val existing = dao.allVideos().associateBy { it.id }

        // remove videos deleted from the phone
        val alive = media.allVideoIds()
        val gone = existing.keys.filter { it !in alive }
        if (gone.isNotEmpty()) { deleteThumbs(gone); dao.deleteFacesOf(gone); dao.deleteVideos(gone) }

        val todo = ArrayList<VideoEntity>()
        for (m in found) {
            val old = existing[m.id]
            val changed = old == null || old.sizeBytes != m.sizeBytes || old.dateModified != m.dateModified
            val v = (old ?: VideoEntity(m.id, m.uri.toString(), m.name, m.relativePath, m.durationMs, m.sizeBytes, m.dateModified))
                .copy(relativePath = m.relativePath, name = m.name, uri = m.uri.toString())
            if (changed || !v.scanned) {
                if (old != null) { deleteThumbs(listOf(m.id)); dao.deleteFacesOf(listOf(m.id)) }
                todo += v.copy(sizeBytes = m.sizeBytes, dateModified = m.dateModified, scanned = false, faceCount = 0)
            } else if (old != v) dao.updateVideo(v)
        }

        if (todo.isNotEmpty()) {
            onProgress(Progress("Loading face model", 0, todo.size))
            val emb = FaceEmbedder.get(context)
            val mode = prefs.scanMode
            val next = AtomicInteger(0)
            val done = AtomicInteger(0)
            coroutineScope {
                repeat(WORKERS) {
                    launch(Dispatchers.IO) {
                        FaceExtractor(context, emb).use { ex ->
                            while (true) {
                                ensureActive()
                                val i = next.getAndIncrement()
                                if (i >= todo.size) break
                                val v = todo[i]
                                val faces = ex.extract(v.id, Uri.parse(v.uri), mode)
                                saveCondensed(v, faces)
                                onProgress(Progress("Scanning faces (${mode.label}, ${emb.backend})", done.incrementAndGet(), todo.size))
                            }
                        }
                    }
                }
            }
        }
        onProgress(Progress("Grouping faces", todo.size, todo.size))
        regroup()
    }

    /**
     * Store a video's faces condensed: per person-in-this-video keep the best few faces, with weights that
     * preserve how many frames they appeared in. Keeps the DB and clustering small even in Thorough mode.
     */
    private suspend fun saveCondensed(v: VideoEntity, faces: List<ExtractedFace>) {
        val kept = Clustering.condense(faces.map { it.embedding }, faces.map { it.quality }, prefs.clusterThreshold, KEEP_PER_PERSON)
        val keptIdx = kept.map { it.first }.toSet()
        faces.forEachIndexed { i, f -> if (i !in keptIdx) File(f.thumbPath).delete() }
        db.runInTransactionSuspend {
            dao.insertFaces(kept.map { (i, w) ->
                val f = faces[i]
                FaceEntity(videoId = v.id, timeMs = f.timeMs, embedding = EmbeddingCodec.toBytes(f.embedding),
                    thumbPath = f.thumbPath, quality = f.quality, weight = w)
            })
            dao.upsertVideo(v.copy(scanned = true, faceCount = faces.size))
        }
    }

    /**
     * "Add person from photo": the user picks photos from their own gallery and types a name. The largest face
     * in each photo becomes an exemplar of that named person; videos are then re-matched.
     * @return (faces added, photos without a usable face)
     */
    suspend fun addPersonFromPhotos(name: String, uris: List<Uri>): Pair<Int, Int> {
        val clean = name.trim()
        require(clean.isNotEmpty())
        val emb = FaceEmbedder.get(context)
        val found = ArrayList<ExtractedFace>()
        var missed = 0
        FaceExtractor(context, emb).use { ex ->
            for ((k, uri) in uris.withIndex()) {
                val bmp = try { decodePhoto(uri) } catch (e: Exception) { null }
                val f = bmp?.let { ex.extractFromPhoto("$k", it) }
                bmp?.recycle()
                if (f == null) missed++ else found += f
            }
        }
        if (found.isEmpty()) return 0 to missed
        db.runInTransactionSuspend {
            val g = dao.groupByName(clean)
            val gid = g?.id ?: dao.insertGroup(GroupEntity(name = clean, thumbPath = found.first().thumbPath))
            if (g != null && g.thumbPath == null) dao.updateGroup(g.copy(thumbPath = found.first().thumbPath))
            dao.insertFaces(found.map {
                FaceEntity(videoId = PHOTO_VIDEO_ID, timeMs = 0, embedding = EmbeddingCodec.toBytes(it.embedding),
                    thumbPath = it.thumbPath, quality = PHOTO_QUALITY, groupId = gid)
            })
        }
        regroup()
        return found.size to missed
    }

    private fun decodePhoto(uri: Uri): Bitmap {
        val src = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width; val h = info.size.height
            val s = minOf(1f, 1600f / maxOf(w, h))
            if (s < 1f) dec.setTargetSize((w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1))
        }.let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false) }
    }

    /** Match unlabeled faces to known people, re-cluster the rest, then re-assign every video. */
    suspend fun regroup() = regroupLock.withLock { regroupLocked() }

    private suspend fun regroupLocked() {
        val groups = dao.allGroups()
        val named = groups.filter { it.name != null }.map { it.id }.toSet()
        val faces = dao.allFaces()

        // exemplars of known people (cap per person, keep the best-quality faces)
        val people = faces.filter { it.groupId in named }.groupBy { it.groupId!! }
            .mapValues { (_, fs) -> fs.sortedByDescending { it.quality }.take(MAX_EXEMPLARS).map { EmbeddingCodec.fromBytes(it.embedding) } }

        val unlabeled = faces.filter { it.groupId == null || it.groupId !in named }
        val matched = HashMap<Long, MutableList<Long>>()
        val rest = ArrayList<FaceEntity>()
        for (f in unlabeled) {
            val m = if (people.isEmpty()) null
                else Clustering.matchKnown(EmbeddingCodec.fromBytes(f.embedding), people, prefs.matchThreshold)
            if (m != null) matched.getOrPut(m.personId) { mutableListOf() } += f.id else rest += f
        }

        val labels = Clustering.cluster(rest.map { EmbeddingCodec.fromBytes(it.embedding) }, prefs.clusterThreshold)

        db.runInTransactionSuspend {
            dao.clearUnnamedFaceGroups()
            dao.deleteUnnamedGroups()
            for ((pid, ids) in matched) ids.chunked(500).forEach { dao.setFaceGroup(it, pid) }
            val byLabel = rest.indices.filter { labels[it] >= 0 }.groupBy { labels[it] }
            for ((_, idxs) in byLabel) {
                val fs = idxs.map { rest[it] }
                val thumb = fs.maxByOrNull { it.quality }?.thumbPath
                val gid = dao.insertGroup(GroupEntity(name = null, thumbPath = thumb))
                fs.map { it.id }.chunked(500).forEach { dao.setFaceGroup(it, gid) }
            }
            reassignVideosLocked()
        }
    }

    private suspend fun reassignVideosLocked() {
        val faces = dao.allFaces().groupBy { it.videoId }
        val updates = dao.allVideos().map { v ->
            val fs = faces[v.id].orEmpty()
            val a = Clustering.assignVideo(fs.map { it.groupId }, fs.map { it.weight }, minShare = prefs.minShare)
            v.copy(autoGroupId = a.groupId, review = a.review && v.manualGroupId == null, confidence = a.confidence)
        }
        dao.updateVideos(updates)
    }

    suspend fun reassignVideos() = db.runInTransactionSuspend { reassignVideosLocked() }

    /** Name (or rename) a group. If another person already has that name, the groups are merged. */
    suspend fun nameGroup(groupId: Long, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        db.runInTransactionSuspend {
            val other = dao.groupByName(clean)
            if (other != null && other.id != groupId) mergeLocked(groupId, other.id)
            else dao.group(groupId)?.let { dao.updateGroup(it.copy(name = clean)) }
            reassignVideosLocked()
        }
    }

    /** Merge [from] into [into]. */
    suspend fun merge(from: Long, into: Long) {
        if (from == into) return
        db.runInTransactionSuspend { mergeLocked(from, into); reassignVideosLocked() }
    }

    private suspend fun mergeLocked(from: Long, into: Long) {
        val a = dao.group(from) ?: return
        val b = dao.group(into) ?: return
        dao.moveFaces(from, into)
        dao.moveManual(from, into)
        dao.updateGroup(b.copy(name = b.name ?: a.name, thumbPath = b.thumbPath ?: a.thumbPath))
        dao.deleteGroup(from)
    }

    /**
     * Manually put a video in a named person (or a new person by name). The video's faces that belonged to its
     * previous dominant group (or were unknown) are relabeled, so the app learns from the correction.
     */
    suspend fun moveVideo(videoId: Long, targetGroupId: Long?, newPersonName: String? = null) {
        db.runInTransactionSuspend {
            val v = dao.video(videoId) ?: return@runInTransactionSuspend
            val target = when {
                !newPersonName.isNullOrBlank() -> {
                    dao.groupByName(newPersonName.trim())?.id
                        ?: dao.insertGroup(GroupEntity(name = newPersonName.trim(),
                            thumbPath = dao.facesOf(videoId).maxByOrNull { it.quality }?.thumbPath))
                }
                else -> targetGroupId
            }
            if (target == null) {
                // send to Review
                dao.updateVideo(v.copy(manualGroupId = null, review = true))
                return@runInTransactionSuspend
            }
            val prev = v.manualGroupId ?: v.autoGroupId
            val relabel = dao.facesOf(videoId).filter { it.groupId == prev || it.groupId == null }.map { it.id }
            if (relabel.isNotEmpty()) dao.setFaceGroup(relabel, target)
            dao.updateVideo(v.copy(manualGroupId = target, review = false))
            reassignVideosLocked()
        }
    }

    suspend fun resetAll() {
        db.runInTransactionSuspend { dao.clearFaces(); dao.clearVideos(); dao.clearGroups() }
        File(context.filesDir, "thumbs").deleteRecursively()
    }

    private suspend fun deleteThumbs(videoIds: List<Long>) {
        for (id in videoIds) dao.facesOf(id).forEach { File(it.thumbPath).delete() }
    }

    companion object {
        /** Faces kept per person per video after condensing. */
        const val KEEP_PER_PERSON = 6
        /** Videos processed in parallel (each worker has its own detector; the ArcFace session is shared). */
        const val WORKERS = 2
        /** videoId used for faces that come from user-picked photos rather than a video. */
        const val PHOTO_VIDEO_ID = -1L
        const val PHOTO_QUALITY = 1_000_000f
        private val regroupLock = Mutex()
        const val MAX_EXEMPLARS = 200
    }
}

suspend fun <T> AppDatabase.runInTransactionSuspend(block: suspend () -> T): T =
    this.withTransaction { block() }
