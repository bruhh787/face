package com.facesort.app.scan

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.facesort.app.FaceSortApp
import com.facesort.app.R

class ScanWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = info("Preparing scan…", 0, 0)

    private fun info(text: String, done: Int, total: Int): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, FaceSortApp.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("FaceSort is scanning videos")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(total, done, total == 0)
            .build()
        return ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun doWork(): Result {
        try { setForeground(getForegroundInfo()) } catch (_: Exception) { /* still run in background */ }
        return try {
            Pipeline(applicationContext).scan { p ->
                setProgress(workDataOf(KEY_PHASE to p.phase, KEY_DONE to p.done, KEY_TOTAL to p.total))
                try {
                    setForeground(info("${p.phase}: ${p.done}/${p.total}", p.done, p.total))
                } catch (_: Exception) {}
            }
            Result.success()
        } catch (e: com.facesort.app.ml.FaceModelUnavailableException) {
            Result.failure(workDataOf(KEY_PHASE to MODEL_MSG, KEY_MODEL_MISSING to true))
        } catch (e: Exception) {
            android.util.Log.e("ScanWorker", "scan failed", e)
            Result.failure(workDataOf(KEY_PHASE to (e.message ?: "error")))
        }
    }

    companion object {
        const val NAME = "facesort-scan"
        const val NOTIF_ID = 42
        const val KEY_PHASE = "phase"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_MODEL_MISSING = "modelMissing"
        const val MODEL_MSG = "The face-detection model could not be loaded. Tap Retry; videos already scanned are kept."

        fun start(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<ScanWorker>().build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, req)
        }
        fun cancel(ctx: Context) = WorkManager.getInstance(ctx).cancelUniqueWork(NAME)
    }
}
