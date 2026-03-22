package com.fersaiyan.cyanbridge.media

import android.os.Environment

object SyncedMediaFolder {
    private const val SUB_FOLDER = "CyanBridge"

    val relativePath: String = "${Environment.DIRECTORY_DCIM}/$SUB_FOLDER"
    val relativePathWithTrailingSlash: String = "$relativePath/"

    fun relativePathLikePattern(): String = "$relativePath/%"

    fun legacyAbsolutePathLikePattern(): String = "%/${Environment.DIRECTORY_DCIM}/$SUB_FOLDER/%"
}
