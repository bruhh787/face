package com.facesort.app.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Entity(tableName = "videos")
data class VideoEntity(
    @PrimaryKey val id: Long,           // MediaStore _ID (stable across RELATIVE_PATH moves)
    val uri: String,
    val name: String,
    val relativePath: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateModified: Long,
    val scanned: Boolean = false,
    val faceCount: Int = 0,
    val autoGroupId: Long? = null,
    val manualGroupId: Long? = null,
    val review: Boolean = false,
    val confidence: Float = 0f,
)

@Entity(tableName = "faces", indices = [Index("videoId"), Index("groupId")])
data class FaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: Long,
    val timeMs: Long,
    val embedding: ByteArray,
    val thumbPath: String,
    val quality: Float,
    val groupId: Long? = null,
    /** How many sampled frames this stored face stands for (faces are condensed per video). */
    val weight: Int = 1,
)

/** A group of faces. name == null -> unnamed cluster awaiting a label; name != null -> a known person. */
@Entity(tableName = "person_groups")
data class GroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String? = null,
    val thumbPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

data class GroupWithCount(
    val id: Long,
    val name: String?,
    val thumbPath: String?,
    val videoCount: Int,
    val faceCount: Int,
)

private const val EFFECTIVE_IN_GROUP =
    "(v.manualGroupId = g.id OR (v.manualGroupId IS NULL AND v.review = 0 AND v.autoGroupId = g.id))"

@Dao
interface FaceSortDao {
    // videos
    @Query("SELECT * FROM videos") suspend fun allVideos(): List<VideoEntity>
    @Query("SELECT * FROM videos WHERE id = :id") suspend fun video(id: Long): VideoEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertVideo(v: VideoEntity)
    @Update suspend fun updateVideo(v: VideoEntity)
    @Update suspend fun updateVideos(v: List<VideoEntity>)
    @Query("DELETE FROM videos WHERE id IN (:ids)") suspend fun deleteVideos(ids: List<Long>)
    @Query("UPDATE videos SET relativePath = :path WHERE id = :id") suspend fun setVideoPath(id: Long, path: String)

    @Query(
        "SELECT v.* FROM videos v, person_groups g WHERE g.id = :groupId AND $EFFECTIVE_IN_GROUP ORDER BY v.name"
    )
    fun videosInGroup(groupId: Long): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos WHERE manualGroupId IS NULL AND review = 1 ORDER BY name")
    fun reviewVideos(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos WHERE scanned = 1 AND faceCount = 0 AND manualGroupId IS NULL ORDER BY name")
    fun noFaceVideos(): Flow<List<VideoEntity>>

    @Query("SELECT COUNT(*) FROM videos") fun videoCount(): Flow<Int>

    // faces
    @Insert suspend fun insertFaces(f: List<FaceEntity>)
    @Query("SELECT * FROM faces") suspend fun allFaces(): List<FaceEntity>
    @Query("SELECT * FROM faces WHERE videoId = :videoId") suspend fun facesOf(videoId: Long): List<FaceEntity>
    @Query("SELECT * FROM faces WHERE groupId = :groupId ORDER BY quality DESC LIMIT :limit")
    fun facesInGroup(groupId: Long, limit: Int): Flow<List<FaceEntity>>
    @Query("SELECT * FROM faces WHERE groupId = :groupId") suspend fun facesInGroupNow(groupId: Long): List<FaceEntity>
    @Query("UPDATE faces SET groupId = :to WHERE groupId = :from") suspend fun moveFaces(from: Long, to: Long)
    @Query("UPDATE faces SET groupId = :groupId WHERE id IN (:ids)") suspend fun setFaceGroup(ids: List<Long>, groupId: Long?)
    @Query("DELETE FROM faces WHERE videoId IN (:ids)") suspend fun deleteFacesOf(ids: List<Long>)
    @Query("SELECT COUNT(*) FROM faces WHERE groupId = :groupId AND videoId = -1") suspend fun photoFaceCount(groupId: Long): Int

    // groups
    @Insert suspend fun insertGroup(g: GroupEntity): Long
    @Update suspend fun updateGroup(g: GroupEntity)
    @Query("SELECT * FROM person_groups") suspend fun allGroups(): List<GroupEntity>
    @Query("SELECT * FROM person_groups WHERE id = :id") suspend fun group(id: Long): GroupEntity?
    @Query("SELECT * FROM person_groups WHERE id = :id") fun groupFlow(id: Long): Flow<GroupEntity?>
    @Query("SELECT * FROM person_groups WHERE name IS NOT NULL ORDER BY name COLLATE NOCASE") fun namedGroups(): Flow<List<GroupEntity>>
    @Query("SELECT * FROM person_groups WHERE name = :name COLLATE NOCASE LIMIT 1") suspend fun groupByName(name: String): GroupEntity?
    @Query("DELETE FROM person_groups WHERE id = :id") suspend fun deleteGroup(id: Long)
    @Query("DELETE FROM person_groups WHERE name IS NULL") suspend fun deleteUnnamedGroups()
    @Query("UPDATE faces SET groupId = NULL WHERE groupId IN (SELECT id FROM person_groups WHERE name IS NULL)")
    suspend fun clearUnnamedFaceGroups()
    @Query("UPDATE videos SET manualGroupId = :to WHERE manualGroupId = :from") suspend fun moveManual(from: Long, to: Long)

    @Query(
        "SELECT g.id, g.name, g.thumbPath, " +
            "(SELECT COUNT(*) FROM videos v WHERE $EFFECTIVE_IN_GROUP) AS videoCount, " +
            "(SELECT COUNT(*) FROM faces f WHERE f.groupId = g.id) AS faceCount " +
            "FROM person_groups g ORDER BY (g.name IS NULL), videoCount DESC, faceCount DESC"
    )
    fun groupsWithCounts(): Flow<List<GroupWithCount>>

    @Query("DELETE FROM videos") suspend fun clearVideos()
    @Query("DELETE FROM faces") suspend fun clearFaces()
    @Query("DELETE FROM person_groups") suspend fun clearGroups()
}

@Database(entities = [VideoEntity::class, FaceEntity::class, GroupEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): FaceSortDao

    companion object {
        @Volatile private var inst: AppDatabase? = null
        fun get(ctx: Context): AppDatabase = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "facesort.db")
                // v1 stored FaceNet embeddings, which are incompatible with ArcFace: wipe and rescan.
                .fallbackToDestructiveMigration()
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onDestructiveMigration(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        java.io.File(ctx.applicationContext.filesDir, "thumbs").deleteRecursively()
                        com.facesort.app.Prefs(ctx.applicationContext).modelUpgradeNotice = true
                    }
                })
                .build().also { inst = it }
        }
    }
}

object EmbeddingCodec {
    fun toBytes(v: FloatArray): ByteArray {
        val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.asFloatBuffer().put(v)
        return bb.array()
    }
    fun fromBytes(b: ByteArray): FloatArray {
        val fb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }
}
