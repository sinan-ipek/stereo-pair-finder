package com.stereopairfinder.model

/**
 * Retained as a small, pure compatibility policy for existing tests. Gallery
 * scanning itself now covers every visible MediaStore image and excludes only
 * the application's output directory.
 */
object CameraFolderPolicy {
    const val PRIMARY_VOLUME = "external_primary"
    const val CAMERA_PATH = "DCIM/Camera/"

    fun accepts(volume: String, relativePath: String): Boolean =
        volume == PRIMARY_VOLUME && relativePath == CAMERA_PATH
}
