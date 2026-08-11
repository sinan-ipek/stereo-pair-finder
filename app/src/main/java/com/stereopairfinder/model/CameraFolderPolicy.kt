package com.stereopairfinder.model

object CameraFolderPolicy {
    const val PRIMARY_VOLUME = "external_primary"
    const val CAMERA_PATH = "DCIM/Camera/"

    fun accepts(volume: String, relativePath: String): Boolean =
        volume == PRIMARY_VOLUME && relativePath == CAMERA_PATH
}
