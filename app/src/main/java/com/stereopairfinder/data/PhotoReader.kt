package com.stereopairfinder.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.stereopairfinder.model.Photo
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.max

class PhotoReader(private val resolver: ContentResolver) {
    fun metadata(uri: Uri): Photo {
        val exifTime = runCatching {
            resolver.openInputStream(uri)?.use { parseExif(ExifInterface(it)) }
        }.getOrNull()

        var dateTaken: Long? = null
        var modified: Long? = null
        var label = "Seçili fotoğraf"
        resolver.query(
            uri,
            arrayOf("datetaken", "date_modified", "_display_name"),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                dateTaken = cursor.longOrNull(0)?.takeIf { it > 0 }
                modified = cursor.longOrNull(1)?.takeIf { it > 0 }?.times(1000)
                label = cursor.getString(2) ?: label
            }
        }
        return Photo(uri, exifTime ?: dateTaken ?: modified, label)
    }

    fun bitmap(uri: Uri, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openFileDescriptor(uri, "r")!!.use {
            BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, bounds)
        }

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
        val decoded = resolver.openFileDescriptor(uri, "r")!!.use {
            BitmapFactory.decodeFileDescriptor(
                it.fileDescriptor,
                null,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            )
        } ?: error("Fotoğraf çözümlenemedi")

        val orientation = resolver.openInputStream(uri)!!.use {
            ExifInterface(it).rotationDegrees
        }
        val rotated = if (orientation == 0) {
            decoded
        } else {
            Bitmap.createBitmap(
                decoded,
                0,
                0,
                decoded.width,
                decoded.height,
                Matrix().apply { postRotate(orientation.toFloat()) },
                true
            ).also { decoded.recycle() }
        }

        if (max(rotated.width, rotated.height) <= maxSide) return rotated
        val scale = maxSide.toFloat() / max(rotated.width, rotated.height)
        return Bitmap.createScaledBitmap(
            rotated,
            (rotated.width * scale).toInt(),
            (rotated.height * scale).toInt(),
            true
        ).also { if (it !== rotated) rotated.recycle() }
    }

    private fun android.database.Cursor.longOrNull(index: Int): Long? =
        if (isNull(index)) null else getLong(index)

    private fun parseExif(exif: ExifInterface): Long? = runCatching {
        val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: return null
        val base = LocalDateTime.parse(raw, DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))
        val sub = exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL)
            ?.filter(Char::isDigit)
            ?.take(3)
            ?.padEnd(3, '0')
            ?.toIntOrNull() ?: 0
        val offset = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
            ?.let(ZoneOffset::of)
        if (offset != null) {
            OffsetDateTime.of(base.withNano(sub * 1_000_000), offset).toInstant().toEpochMilli()
        } else {
            base.withNano(sub * 1_000_000).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
    }.getOrNull()
}
