package com.facesort.app.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.facesort.app.cluster.VecMath
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * InsightFace ArcFace (ResNet-50 trained on WebFace600K, "w600k_r50", fp32) via ONNX Runtime on the CPU.
 * Input: 1..N x 3 x 112 x 112, RGB, (x - 127.5) / 127.5, face aligned to the ArcFace 5-point template.
 * Output: 512-d embedding (we L2-normalise; cosine similarity = dot product).
 *
 * One process-wide session is shared by all scan workers (OrtSession.run is thread-safe).
 * Licence: InsightFace pretrained models are for non-commercial research purposes only.
 */
class FaceEmbedder private constructor(context: Context) {
    private val env = OrtEnvironment.getEnvironment()
    private val modelBuffer: MappedByteBuffer = mapAsset(context) // must stay referenced while the session lives
    private val session: OrtSession
    private val inputName: String
    val backend: String

    init {
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(THREADS)
            setInterOpNumThreads(1)
        }
        val t0 = System.currentTimeMillis()
        session = env.createSession(modelBuffer, opts)
        inputName = session.inputNames.first()
        backend = "ArcFace CPU×$THREADS"
        Log.i(TAG, "ArcFace session ready in ${System.currentTimeMillis() - t0} ms")
    }

    /** @param face aligned 112x112 ARGB crop. Returns L2-normalised embedding (avg of original + mirror). */
    fun embed(face: Bitmap): FloatArray {
        val plane = SIZE * SIZE
        val pixels = IntArray(plane)
        face.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        val buf = ByteBuffer.allocateDirect(2 * 3 * plane * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (img in 0..1) {
            val base = img * 3 * plane
            for (y in 0 until SIZE) for (x in 0 until SIZE) {
                val sx = if (img == 1) SIZE - 1 - x else x
                val p = pixels[y * SIZE + sx]
                val i = y * SIZE + x
                buf.put(base + i, ((p shr 16 and 0xFF) - 127.5f) / 127.5f)
                buf.put(base + plane + i, ((p shr 8 and 0xFF) - 127.5f) / 127.5f)
                buf.put(base + 2 * plane + i, ((p and 0xFF) - 127.5f) / 127.5f)
            }
        }
        buf.rewind()
        val out: Array<FloatArray> = OnnxTensor.createTensor(env, buf, longArrayOf(2, 3, SIZE.toLong(), SIZE.toLong())).use { t ->
            session.run(mapOf(inputName to t)).use { r ->
                @Suppress("UNCHECKED_CAST")
                (r[0].value as Array<FloatArray>).map { it.copyOf() }.toTypedArray()
            }
        }
        val a = VecMath.normalize(out[0]); val b = VecMath.normalize(out[1])
        return VecMath.normalize(FloatArray(DIM) { a[it] + b[it] })
    }

    companion object {
        const val TAG = "FaceEmbedder"
        const val MODEL = "arcface_w600k_r50.onnx"
        const val SIZE = 112
        const val DIM = 512
        const val THREADS = 4

        @Volatile private var inst: FaceEmbedder? = null

        /** Loads the ~174 MB model once per process (takes a second or two). */
        fun get(ctx: Context): FaceEmbedder = inst ?: synchronized(this) {
            inst ?: FaceEmbedder(ctx.applicationContext).also { inst = it }
        }

        private fun mapAsset(ctx: Context): MappedByteBuffer {
            val fd = try { ctx.assets.openFd(MODEL) } catch (e: FileNotFoundException) {
                throw IllegalStateException("Face model $MODEL is missing from the APK (build with scripts/fetch_model.sh)", e)
            }
            fd.use {
                FileInputStream(it.fileDescriptor).use { fis ->
                    return fis.channel.map(FileChannel.MapMode.READ_ONLY, it.startOffset, it.declaredLength)
                }
            }
        }
    }
}
