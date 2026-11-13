package com.facesort.app.media

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

data class MediaVideo(
    val id: Long, val uri: Uri, val name: String, val relativePath: String,
    val durationMs: Long, val sizeBytes: Long, val dateModified: Long,
)

data class Folder(val relativePath: String, val count: Int)

class MediaRepo(private val context: Context) {
    private val collection: Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    fun queryVideos(folders: Set<String>): List<MediaVideo> {
        val proj = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_MODIFIED,
        )
        val out = ArrayList<MediaVideo>()
        context.contentResolver.query(collection, proj, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
            val iId = c.getColumnIndexOrThrow(proj[0]); val iName = c.getColumnIndexOrThrow(proj[1])
            val iPath = c.getColumnIndexOrThrow(proj[2]); val iDur = c.getColumnIndexOrThrow(proj[3])
            val iSize = c.getColumnIndexOrThrow(proj[4]); val iMod = c.getColumnIndexOrThrow(proj[5])
            while (c.moveToNext()) {
                val path = c.getString(iPath) ?: ""
                // selected folders (incl. sub-folders); FaceSort output folders are always included so
                // already-organised videos stay known.
                if (folders.isNotEmpty() && folders.none { path.startsWith(it) } && !path.startsWith(com.facesort.app.Prefs.ROOT_FOLDER)) continue
                val id = c.getLong(iId)
                out += MediaVideo(
                    id, ContentUris.withAppendedId(collection, id), c.getString(iName) ?: "video_$id", path,
                    c.getLong(iDur), c.getLong(iSize), c.getLong(iMod),
                )
            }
        }
        return out
    }

    fun allVideoIds(): Set<Long> {
        val ids = HashSet<Long>()
        context.contentResolver.query(collection, arrayOf(MediaStore.Video.Media._ID), null, null, null)?.use { c ->
            while (c.moveToNext()) ids += c.getLong(0)
        }
        return ids
    }

    fun folders(): List<Folder> {
        val counts = HashMap<String, Int>()
        context.contentResolver.query(collection, arrayOf(MediaStore.Video.Media.RELATIVE_PATH), null, null, null)?.use { c ->
            while (c.moveToNext()) { val p = c.getString(0) ?: continue; counts[p] = (counts[p] ?: 0) + 1 }
        }
        return counts.map { Folder(it.key, it.value) }.sortedBy { it.relativePath.lowercase() }
    }

    /** Move by updating RELATIVE_PATH. Caller must hold write access (createWriteRequest grant). */
    fun move(uri: Uri, relativePath: String): Boolean = try {
        val cv = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath) }
        context.contentResolver.update(uri, cv, null, null) > 0
    } catch (e: Exception) {
        Log.w("MediaRepo", "move failed for $uri", e); false
    }

    companion object {
        fun sanitize(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.').ifEmpty { "Unnamed" }.take(80)
        fun folderFor(name: String) = "${com.facesort.app.Prefs.ROOT_FOLDER}${sanitize(name)}/"
    }
}
