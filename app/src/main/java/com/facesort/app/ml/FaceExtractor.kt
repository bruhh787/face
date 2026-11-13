package com.facesort.app.ml

import android.content.Context
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.facesort.app.ScanMode
import com.facesort.app.cluster.AlignMath
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Thrown when the ML Kit face model cannot be used; aborts the scan instead of marking videos as face-less. */
class FaceModelUnavailableException(cause: Throwable? = null) :
    Exception("Face detection model unavailable", cause)

internal fun Throwable.isModelUnavailable(): Boolean {
    var t: Throwable? = this
    while (t != null) {
        if (t is FaceModelUnavailableException) return true
        if (t is com.google.mlkit.common.MlKitException && t.errorCode == com.google.mlkit.common.MlKitException.UNAVAILABLE) return true
        t = t.cause
    }
    return false
}

data class ExtractedFace(
    val timeMs: Long,
    val embedding: FloatArray,
    val thumbPath: String,
    val quality: Float,
)

/**
 * Samples frames from a video (rotation-aware, up to 1280 px), detects faces with ML Kit (bundled model,
 * landmarks on), aligns each face to the ArcFace template using eyes + mouth corners, and embeds it.
 * One instance per worker thread (owns its detector); the embedder is shared.
 */
class FaceExtractor(private val context: Context, private val embedder: FaceEmbedder) : AutoCloseable {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setMinFaceSize(0.05f)
            .build()
    )
    private val thumbDir = File(context.filesDir, "thumbs").apply { mkdirs() }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    suspend fun extract(videoId: Long, uri: Uri, mode: ScanMode): List<ExtractedFace> {
        val out = ArrayList<ExtractedFace>()
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, uri)
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val vw = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val vh = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val turned = rot == 90 || rot == 270
            val dispW = if (turned) vh else vw
            val dispH = if (turned) vw else vh
            val option = if (mode.exactSeek) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC

            var lastSig: IntArray? = null
            for (t in ScanMode.sampleTimes(dur, mode)) {
                currentCoroutineContext().ensureActive()
                val bmp = grab(mmr, t * 1000, option, dispW, dispH) ?: continue
                try {
                    val sig = FrameSig.of(bmp)
                    if (lastSig != null && FrameSig.nearlySame(sig, lastSig)) continue // seek returned the same picture
                    lastSig = sig
                    out += processFrame(videoId, t, bmp, strict = true, maxFaces = MAX_FACES_PER_FRAME)
                } catch (e: Exception) {
                    if (e.isModelUnavailable()) throw FaceModelUnavailableException(e)
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "frame failed", e)
                } finally { bmp.recycle() }
            }
        } catch (e: Exception) {
            if (e.isModelUnavailable() || e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "cannot read $uri", e)
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
        return out
    }

    /** Largest face in a still photo the user picked (less strict than video frames). */
    fun extractFromPhoto(tag: String, bmp: Bitmap): ExtractedFace? =
        processFrame(-1, 0, bmp, strict = false, maxFaces = 1, tag = tag).firstOrNull()

    /** Decode directly at <= MAX_DIM in true display proportions (cheaper than full-size decode + resize). */
    private fun grab(r: MediaMetadataRetriever, tUs: Long, option: Int, dw: Int, dh: Int): Bitmap? {
        val raw: Bitmap = try {
            if (dw <= 0 || dh <= 0) {
                r.getFrameAtTime(tUs, option)?.let { shrink(it) }
            } else {
                val s = min(1f, MAX_DIM.toFloat() / max(dw, dh))
                val tw = max(2, (dw * s).roundToInt()); val th = max(2, (dh * s).roundToInt())
                r.getScaledFrameAtTime(tUs, option, tw, th)?.let { b ->
                    // some decoders squeeze rotated clips: restore the true aspect ratio
                    if (abs((b.width.toFloat() / b.height) / (dw.toFloat() / dh) - 1f) > 0.06f) {
                        Bitmap.createScaledBitmap(b, tw, th, true).also { if (it !== b) b.recycle() }
                    } else b
                }
            }
        } catch (e: Exception) { null } ?: return null
        return if (raw.config == Bitmap.Config.ARGB_8888) raw
        else raw.copy(Bitmap.Config.ARGB_8888, false).also { raw.recycle() }
    }

    private fun shrink(src: Bitmap): Bitmap {
        val m = max(src.width, src.height)
        if (m <= MAX_DIM) return src
        val s = MAX_DIM.toFloat() / m
        return Bitmap.createScaledBitmap(src, (src.width * s).roundToInt(), (src.height * s).roundToInt(), true)
            .also { if (it !== src) src.recycle() }
    }

    private fun processFrame(
        videoId: Long, t: Long, bmp: Bitmap, strict: Boolean, maxFaces: Int, tag: String? = null,
    ): List<ExtractedFace> {
        val faces = Tasks.await(detector.process(InputImage.fromBitmap(bmp, 0)))
        val good = faces.filter {
            val b = it.boundingBox
            val minPx = if (strict) MIN_FACE_PX else MIN_FACE_PX_PHOTO
            min(b.width(), b.height()) >= minPx &&
                (!strict || (abs(it.headEulerAngleY) <= 40f && abs(it.headEulerAngleX) <= 30f))
        }.sortedByDescending { it.boundingBox.width() * it.boundingBox.height() }.take(maxFaces)

        return good.mapIndexed { k, f ->
            val crop = align(bmp, f)
            val emb = embedder.embed(crop)
            val name = if (tag != null) "photo_${tag}_${System.nanoTime()}.jpg" else "${videoId}_${t}_$k.jpg"
            val thumb = File(thumbDir, name)
            FileOutputStream(thumb).use { os -> crop.compress(Bitmap.CompressFormat.JPEG, 85, os) }
            crop.recycle()
            val box = f.boundingBox
            val size = min(box.width(), box.height()).toFloat()
            val frontal = 1f - (abs(f.headEulerAngleY) / 40f) * 0.5f - (abs(f.headEulerAngleX) / 30f) * 0.3f
            ExtractedFace(t, emb, thumb.absolutePath, size * frontal.coerceAtLeast(0.1f))
        }
    }

    /**
     * Warp the face onto the 112x112 ArcFace template with a least-squares similarity transform:
     * eyes + mouth corners (4 points) when available, else eyes only, else a roll-levelled box crop.
     * ML Kit's LEFT/RIGHT are the subject's; the template is image-left first, so subject-right comes first.
     */
    private fun align(src: Bitmap, f: Face): Bitmap {
        val s = FaceEmbedder.SIZE
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out).apply { drawColor(Color.BLACK) }
        val re = f.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val le = f.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val mr = f.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position
        val ml = f.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
        val t = when {
            re != null && le != null && mr != null && ml != null ->
                AlignMath.similarity(floatArrayOf(re.x, re.y, le.x, le.y, mr.x, mr.y, ml.x, ml.y), AlignMath.ARCFACE_EYES_MOUTH)
            re != null && le != null ->
                AlignMath.similarity(floatArrayOf(re.x, re.y, le.x, le.y), AlignMath.ARCFACE_EYES)
            else -> null
        }
        val m = Matrix()
        if (t != null && t[0] > 0f) { // a > 0: not flipped/upside-down
            m.setValues(AlignMath.toMatrixValues(t))
        } else {
            val box = f.boundingBox
            val side = max(box.width(), box.height()) * 1.15f
            m.setTranslate(-box.exactCenterX(), -box.exactCenterY())
            m.postRotate(-f.headEulerAngleZ.coerceIn(-45f, 45f))
            m.postScale(s / side, s / side)
            m.postTranslate(s / 2f, s / 2f)
        }
        canvas.drawBitmap(src, m, paint)
        return out
    }

    override fun close() { detector.close() }

    companion object {
        const val TAG = "FaceExtractor"
        const val MAX_DIM = 1280
        const val MIN_FACE_PX = 48
        const val MIN_FACE_PX_PHOTO = 32
        const val MAX_FACES_PER_FRAME = 3
    }
}

/** 16x16 greyscale signature; two frames are "the same" if the mean absolute difference is <= 1 grey level. */
object FrameSig {
    fun of(b: Bitmap): IntArray {
        val small = Bitmap.createScaledBitmap(b, 16, 16, true)
        val px = IntArray(256)
        small.getPixels(px, 0, 16, 0, 0, 16, 16)
        if (small !== b) small.recycle()
        return IntArray(256) { i -> luma(px[i]) }
    }
    fun luma(p: Int) = ((p shr 16 and 0xFF) * 3 + (p shr 8 and 0xFF) * 6 + (p and 0xFF)) / 10
    fun nearlySame(a: IntArray, b: IntArray): Boolean = com.facesort.app.cluster.FrameDiff.nearlySame(a, b)
}
