package com.stereopairfinder.model

/** Future scanner boundary; the test build deliberately contains no scanner or query. */
object CameraFolderPolicy {
    const val PRIMARY_VOLUME = "external_primary"
    const val CAMERA_PATH = "DCIM/Camera/"
    fun accepts(volume: String, relativePath: String) = volume == PRIMARY_VOLUME && relativePath == CAMERA_PATH
}
