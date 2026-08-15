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

    fun uniqueName(now: Instant = Instant.now(), uuid: UUID = UUID.randomUUID()): String =
        "SBS_${DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS").withZone(ZoneOffset.UTC).format(now)}_${uuid}.jpg"

    fun save(bitmap: Bitmap): Saved = saveNewJpeg { stream ->
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) { "JPEG kodlanamadı" }
    }

    fun saveJpeg(jpeg: ByteArray): Saved {
        require(jpeg.isNotEmpty()) { "JPEG verisi boş" }
        return saveNewJpeg { stream -> stream.write(jpeg) }
    }

    private fun saveNewJpeg(write: (java.io.OutputStream) -> Unit): Saved {
        val name = uniqueName()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, OUTPUT_PATH)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val outputUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore yeni çıktı kaydı oluşturamadı")
        try {
            resolver.openOutputStream(outputUri, "w")!!.use(write)
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

    companion object {
        const val OUTPUT_PATH = "Pictures/Stereo SBS/"
        const val LEGACY_OUTPUT_PATH = "Pictures/Stereo SBS Test/"
    }
}
