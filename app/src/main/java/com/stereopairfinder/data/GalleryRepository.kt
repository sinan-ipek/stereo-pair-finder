package com.stereopairfinder.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.stereopairfinder.model.PairPolicy
import com.stereopairfinder.model.Photo

data class VolumeSnapshot(
    val volume: String,
    val version: String,
    val generation: Long,
    val dateAdded: Long,
    val id: Long,
    val usesGeneration: Boolean
)

data class GalleryBatch(
    val photos: List<Photo>,
    val snapshots: List<VolumeSnapshot>,
    val firstScan: Boolean
)

object ScanBoundaryPolicy {
    /**
     * One unpaired new photo is intentionally kept pending. The next scan can
     * then compare it with the following photo without ever comparing it with
     * the previous scan's final image.
     */
    fun shouldCommit(photoCount: Int): Boolean = photoCount != 1

    fun isApplicationOutput(relativePath: String?): Boolean =
        relativePath?.trimEnd('/')?.equals(
            SbsSaver.OUTPUT_PATH.trimEnd('/'),
            ignoreCase = true
        ) == true
}

class GalleryRepository(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun prepareBatch(): GalleryBatch {
        val volumes = MediaStore.getExternalVolumeNames(appContext).sorted()
        val snapshots = volumes.map(::captureSnapshot)
        val firstScan = snapshots.none { hasUsableCheckpoint(it) }
        val photos = snapshots.flatMap { snapshot ->
            queryNewPhotos(snapshot, readCheckpoint(snapshot.volume))
        }
        return GalleryBatch(
            photos = PairPolicy.sorted(photos),
            snapshots = snapshots,
            firstScan = firstScan
        )
    }

    fun commit(batch: GalleryBatch, completedAtMillis: Long = System.currentTimeMillis()) {
        val editor = preferences.edit()
        batch.snapshots.forEach { snapshot ->
            val prefix = volumePrefix(snapshot.volume)
            editor
                .putString(prefix + VERSION, snapshot.version)
                .putLong(prefix + GENERATION, snapshot.generation)
                .putLong(prefix + DATE_ADDED, snapshot.dateAdded)
                .putLong(prefix + ID, snapshot.id)
                .putBoolean(prefix + USES_GENERATION, snapshot.usesGeneration)
        }
        editor.putLong(LAST_SCAN, completedAtMillis).apply()
    }

    fun lastScanMillis(): Long? =
        preferences.getLong(LAST_SCAN, 0L).takeIf { it > 0L }

    private fun captureSnapshot(volume: String): VolumeSnapshot {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return VolumeSnapshot(
                volume = volume,
                version = MediaStore.getVersion(appContext, volume),
                generation = MediaStore.getGeneration(appContext, volume),
                dateAdded = 0L,
                id = 0L,
                usesGeneration = true
            )
        }

        val uri = MediaStore.Images.Media.getContentUri(volume)
        var newestDate = 0L
        var newestId = 0L
        resolver.query(
            uri,
            arrayOf(MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media._ID),
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                newestDate = cursor.getLong(0)
                newestId = cursor.getLong(1)
            }
        }
        return VolumeSnapshot(
            volume = volume,
            version = MediaStore.getVersion(appContext, volume),
            generation = 0L,
            dateAdded = newestDate,
            id = newestId,
            usesGeneration = false
        )
    }

    private fun queryNewPhotos(
        snapshot: VolumeSnapshot,
        checkpoint: VolumeSnapshot?
    ): List<Photo> {
        val uri = MediaStore.Images.Media.getContentUri(snapshot.volume)
        val validCheckpoint = checkpoint?.takeIf {
            it.version == snapshot.version &&
                it.usesGeneration == snapshot.usesGeneration
        }
        val projection = buildList {
            add(MediaStore.Images.Media._ID)
            add(MediaStore.Images.Media.DISPLAY_NAME)
            add(MediaStore.Images.Media.DATE_TAKEN)
            add(MediaStore.Images.Media.DATE_ADDED)
            add(MediaStore.Images.Media.DATE_MODIFIED)
            add(MediaStore.Images.Media.RELATIVE_PATH)
            if (snapshot.usesGeneration) add(MediaStore.Images.Media.GENERATION_ADDED)
        }.toTypedArray()

        val (selection, arguments) = if (snapshot.usesGeneration) {
            val lower = validCheckpoint?.generation ?: 0L
            (
                "${MediaStore.Images.Media.IS_PENDING}=0 AND " +
                    "${MediaStore.Images.Media.GENERATION_ADDED}>? AND " +
                    "${MediaStore.Images.Media.GENERATION_ADDED}<=?"
                ) to arrayOf(lower.toString(), snapshot.generation.toString())
        } else {
            val lowerDate = validCheckpoint?.dateAdded ?: 0L
            val lowerId = validCheckpoint?.id ?: 0L
            (
                "${MediaStore.Images.Media.IS_PENDING}=0 AND " +
                    "(${MediaStore.Images.Media.DATE_ADDED}>? OR " +
                    "(${MediaStore.Images.Media.DATE_ADDED}=? AND ${MediaStore.Images.Media._ID}>?)) AND " +
                    "(${MediaStore.Images.Media.DATE_ADDED}<? OR " +
                    "(${MediaStore.Images.Media.DATE_ADDED}=? AND ${MediaStore.Images.Media._ID}<=?))"
                ) to arrayOf(
                lowerDate.toString(),
                lowerDate.toString(),
                lowerId.toString(),
                snapshot.dateAdded.toString(),
                snapshot.dateAdded.toString(),
                snapshot.id.toString()
            )
        }

        val photos = mutableListOf<Photo>()
        resolver.query(
            uri,
            projection,
            selection,
            arguments,
            "${MediaStore.Images.Media.DATE_TAKEN} ASC, ${MediaStore.Images.Media._ID} ASC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val takenIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)

            while (cursor.moveToNext()) {
                val relativePath = cursor.stringOrNull(pathIndex)
                if (ScanBoundaryPolicy.isApplicationOutput(relativePath)) continue

                val id = cursor.getLong(idIndex)
                val photoUri: Uri = ContentUris.withAppendedId(uri, id)
                val time = cursor.longOrNull(takenIndex)?.takeIf { it > 0L }
                    ?: cursor.longOrNull(addedIndex)?.takeIf { it > 0L }?.times(1000L)
                    ?: cursor.longOrNull(modifiedIndex)?.takeIf { it > 0L }?.times(1000L)
                photos += Photo(
                    uri = photoUri,
                    takenAtMillis = time,
                    label = cursor.stringOrNull(nameIndex) ?: "Fotoğraf $id",
                    tie = "${snapshot.volume}:$id"
                )
            }
        }
        return photos
    }

    private fun hasUsableCheckpoint(snapshot: VolumeSnapshot): Boolean {
        val saved = readCheckpoint(snapshot.volume) ?: return false
        return saved.version == snapshot.version &&
            saved.usesGeneration == snapshot.usesGeneration
    }

    private fun readCheckpoint(volume: String): VolumeSnapshot? {
        val prefix = volumePrefix(volume)
        if (!preferences.contains(prefix + VERSION)) return null
        return VolumeSnapshot(
            volume = volume,
            version = preferences.getString(prefix + VERSION, "") ?: "",
            generation = preferences.getLong(prefix + GENERATION, 0L),
            dateAdded = preferences.getLong(prefix + DATE_ADDED, 0L),
            id = preferences.getLong(prefix + ID, 0L),
            usesGeneration = preferences.getBoolean(prefix + USES_GENERATION, false)
        )
    }

    private fun android.database.Cursor.longOrNull(index: Int): Long? =
        if (isNull(index)) null else getLong(index)

    private fun android.database.Cursor.stringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun volumePrefix(volume: String) = "volume.$volume."

    companion object {
        private const val PREFS = "gallery_scan_checkpoint"
        private const val VERSION = "version"
        private const val GENERATION = "generation"
        private const val DATE_ADDED = "date_added"
        private const val ID = "id"
        private const val USES_GENERATION = "uses_generation"
        private const val LAST_SCAN = "last_scan"
    }
}
