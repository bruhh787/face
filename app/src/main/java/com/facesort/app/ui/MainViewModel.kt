package com.facesort.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.facesort.app.Prefs
import com.facesort.app.data.AppDatabase
import com.facesort.app.data.VideoEntity
import com.facesort.app.media.Folder
import com.facesort.app.media.MediaRepo
import com.facesort.app.scan.Pipeline
import com.facesort.app.scan.ScanWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Home : Screen
    data class Group(val id: Long) : Screen
    data object Review : Screen
    data object NoFaces : Screen
}

data class ScanState(val running: Boolean, val phase: String, val done: Int, val total: Int, val failed: String?, val modelMissing: Boolean = false)

data class MoveItem(val videoId: Long, val uri: Uri, val target: String)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.get(app).dao()
    private val pipeline = Pipeline(app)
    private val media = MediaRepo(app)
    val prefs = Prefs(app)

    val screen = MutableStateFlow<Screen>(Screen.Home)
    val message = MutableStateFlow<String?>(null)

    val groups = dao.groupsWithCounts().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val named = dao.namedGroups().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val review = dao.reviewVideos().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val noFaces = dao.noFaceVideos().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val videoCount = dao.videoCount().stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val scan: StateFlow<ScanState> = WorkManager.getInstance(app)
        .getWorkInfosForUniqueWorkFlow(ScanWorker.NAME)
        .map { infos ->
            val wi = infos.lastOrNull()
            when (wi?.state) {
                WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ScanState(
                    true, wi.progress.getString(ScanWorker.KEY_PHASE) ?: "Starting…",
                    wi.progress.getInt(ScanWorker.KEY_DONE, 0), wi.progress.getInt(ScanWorker.KEY_TOTAL, 0), null)
                WorkInfo.State.FAILED -> ScanState(false, "", 0, 0, wi.outputData.getString(ScanWorker.KEY_PHASE) ?: "Scan failed",
                    wi.outputData.getBoolean(ScanWorker.KEY_MODEL_MISSING, false))
                else -> ScanState(false, "", 0, 0, null)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ScanState(false, "", 0, 0, null))

    init {
        if (prefs.modelUpgradeNotice) {
            prefs.modelUpgradeNotice = false
            message.value = "Face recognition upgraded to ArcFace. Old results were cleared: tap Scan to rescan."
        }
    }

    fun group(id: Long) = dao.groupFlow(id)
    fun videosIn(id: Long) = dao.videosInGroup(id)
    fun facesIn(id: Long) = dao.facesInGroup(id, 30)

    fun startScan() = ScanWorker.start(getApplication())
    fun cancelScan() = ScanWorker.cancel(getApplication())

    fun nameGroup(id: Long, name: String) = viewModelScope.launch(Dispatchers.IO) { pipeline.nameGroup(id, name) }
    fun merge(from: Long, into: Long) = viewModelScope.launch(Dispatchers.IO) {
        pipeline.merge(from, into)
        withContext(Dispatchers.Main) { screen.value = Screen.Group(into) }
    }
    fun moveVideo(videoId: Long, target: Long?, newName: String? = null) =
        viewModelScope.launch(Dispatchers.IO) { pipeline.moveVideo(videoId, target, newName) }

    fun applySettings(cluster: Float, match: Float, share: Float, mode: com.facesort.app.ScanMode, moveReview: Boolean) {
        val regroup = cluster != prefs.clusterThreshold || match != prefs.matchThreshold || share != prefs.minShare
        prefs.clusterThreshold = cluster; prefs.matchThreshold = match; prefs.minShare = share
        prefs.scanMode = mode; prefs.moveReviewToo = moveReview
        if (regroup) viewModelScope.launch(Dispatchers.IO) { pipeline.regroup(); message.value = "Regrouped with new thresholds" }
    }

    val photoBusy = MutableStateFlow(false)

    /** Teach a person from photos the user picked in their own gallery. */
    fun addPersonFromPhotos(name: String, uris: List<Uri>) {
        if (uris.isEmpty() || name.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            photoBusy.value = true
            try {
                val (added, missed) = pipeline.addPersonFromPhotos(name, uris)
                message.value = when {
                    added == 0 -> "No clear face found in the selected photo(s)."
                    missed > 0 -> "Added $added face(s) to ${name.trim()} ($missed photo(s) had no clear face). Videos re-matched."
                    else -> "Added $added face(s) to ${name.trim()}. Videos re-matched."
                }
            } catch (e: Exception) {
                message.value = "Could not add photos: ${e.message}"
            } finally { photoBusy.value = false }
        }
    }

    fun resetAll() = viewModelScope.launch(Dispatchers.IO) { pipeline.resetAll(); message.value = "All FaceSort data cleared" }

    suspend fun folders(): List<Folder> = withContext(Dispatchers.IO) { media.folders() }
    fun setFolders(f: Set<String>) { prefs.sourceFolders = f }

    // ---------- organise ----------
    val plan = MutableStateFlow<List<MoveItem>?>(null)          // awaiting confirmation
    val pendingChunk = MutableStateFlow<List<MoveItem>?>(null)  // awaiting system write consent
    private var queue = ArrayDeque<List<MoveItem>>()
    private var moved = 0
    private var failed = 0

    fun buildPlan() = viewModelScope.launch(Dispatchers.IO) {
        val names = dao.allGroups().filter { it.name != null }.associate { it.id to it.name!! }
        val items = ArrayList<MoveItem>()
        for (v in dao.allVideos()) {
            val target = targetFor(v, names) ?: continue
            if (v.relativePath == target) continue
            items += MoveItem(v.id, Uri.parse(v.uri), target)
        }
        if (items.isEmpty()) message.value = "Nothing to move. Name some groups first." else plan.value = items
    }

    private fun targetFor(v: VideoEntity, names: Map<Long, String>): String? {
        val gid = v.manualGroupId ?: if (!v.review) v.autoGroupId else null
        val name = gid?.let { names[it] }
        return when {
            name != null -> MediaRepo.folderFor(name)
            prefs.moveReviewToo && v.manualGroupId == null && v.review -> Prefs.REVIEW_FOLDER
            else -> null
        }
    }

    fun confirmPlan() {
        val p = plan.value ?: return
        plan.value = null
        moved = 0; failed = 0
        queue = ArrayDeque(p.chunked(CHUNK))
        pendingChunk.value = queue.removeFirstOrNull()
    }

    fun cancelPlan() { plan.value = null }

    /** Called after the system "Allow FaceSort to modify these videos?" dialog. */
    fun onWriteResult(granted: Boolean) {
        val chunk = pendingChunk.value ?: return
        pendingChunk.value = null
        if (!granted) { queue.clear(); message.value = "Move cancelled. $moved moved so far."; return }
        viewModelScope.launch(Dispatchers.IO) {
            for (item in chunk) {
                if (media.move(item.uri, item.target)) { dao.setVideoPath(item.videoId, item.target); moved++ } else failed++
            }
            val next = queue.removeFirstOrNull()
            if (next != null) pendingChunk.value = next
            else message.value = "Moved $moved video(s) into ${Prefs.ROOT_FOLDER}" + if (failed > 0) " ($failed failed)" else ""
        }
    }

    companion object { const val CHUNK = 400 }
}
