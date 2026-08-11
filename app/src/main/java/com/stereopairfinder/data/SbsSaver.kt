package com.stereopairfinder.data

import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

class SbsSaver(private val resolver: ContentResolver) {
    data class Saved(val name: String, val location: String = OUTPUT_PATH)

    fun uniqueName(
        now: Instant = Instant.now(),
        uuid: UUID = UUID.randomUUID()
    ): String = "SBS_${STAMP.format(now)}_$uuid.jpg"

    fun save(bitmap: Bitmap): Saved = saveNamed(bitmap, uniqueName())

    fun saveAutomatic(bitmap: Bitmap, leftKey: String, rightKey: String): Saved {
        val stableId = UUID.nameUUIDFromBytes("$leftKey|$rightKey".toByteArray(Charsets.UTF_8))
        val name = "SBS_AUTO_$stableId.jpg"
        if (exists(name)) return Saved(name)
        return saveNamed(bitmap, name)
    }

    private fun saveNamed(bitmap: Bitmap, name: String): Saved {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, OUTPUT_PATH)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val outputUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore yeni çıktı kaydı oluşturamadı")
        try {
            resolver.openOutputStream(outputUri, "w")!!.use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) {
                    "JPEG kodlanamadı"
                }
            }
            resolver.update(
                outputUri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null
            )
            return Saved(name)
        } catch (failure: Throwable) {
            resolver.delete(outputUri, null, null)
            throw failure
        }
    }

    private fun exists(name: String): Boolean {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection =
            "${MediaStore.Images.Media.DISPLAY_NAME}=? AND ${MediaStore.Images.Media.RELATIVE_PATH}=?"
        return resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            arrayOf(name, OUTPUT_PATH),
            null
        )?.use { it.moveToFirst() } == true
    }

    companion object {
        const val OUTPUT_PATH = "Pictures/StereoPairFinder/"
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")
            .withZone(ZoneOffset.UTC)
    }
}
